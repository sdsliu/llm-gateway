# llm-gateway

Личный **OpenAI-совместимый LLM-шлюз** на Spring Boot 3 / Java 21. Он принимает запросы в формате
OpenAI Chat Completions и отправляет их бесплатным или дешёвым провайдерам (Groq, Gemini, OpenRouter и др.).
Провайдеры перебираются по приоритету, а при исчерпании лимитов шлюз автоматически переключается на следующего.
Каждый запрос записывается в PostgreSQL, а состояние видно на живом веб-дашборде.

Клиенты (Aider, OpenHands, OpenAI SDK, curl) указывают `http://localhost:4000/v1` как base URL
и ничего не знают о том, какой провайдер на самом деле ответил.

## Возможности

- `POST /v1/chat/completions` и `GET /v1/models` в формате OpenAI. Неизвестные поля игнорируются.
- **Стриминг** `stream: true`: SSE в формате OpenAI (`data: {chunk}` … `data: [DONE]`).
- **Ротация провайдеров** по `priority`. При 429, 5xx, 401/403 и таймаутах провайдер уходит на паузу
  (`Retry-After` или 5 минут), а запрос повторяется у следующего. Если недоступны все — `503` в формате OpenAI.
- **Лимиты**: собственные RPM/RPD на провайдера (скользящие окна) и учёт заголовков
  `x-ratelimit-remaining-*` / `Retry-After`.
- **Подмена модели**: клиент шлёт любое имя (например `openai/coder`), провайдеру уходит модель из конфига.
- **Учёт usage**: таблица `usage_record` (Flyway). В неё попадает каждая попытка: провайдер, модель, токены,
  статус и задержка.
- **Дашборд** на `/`, обновляется в реальном времени через SSE. Есть также JSON `GET /status`.
- **OpenRouter**: остаток кредитов ключа проверяется по расписанию (`GET /api/v1/key`).
- **Docker Compose**: шлюз + PostgreSQL 16 с healthcheck.

## Архитектура

```mermaid
flowchart LR
    C[Клиенты<br/>Aider · OpenHands · curl · OpenAI SDK] -->|POST /v1/chat/completions| API[ChatController<br/>api]
    API --> R[ProviderRouter<br/>router]
    R -->|priority 1| G[Groq]
    R -->|priority 2| GM[Gemini]
    R -->|priority 3| OR[OpenRouter]
    R -. состояние: cooldown, RPM/RPD,<br/>x-ratelimit-* .-> S[(ProviderState<br/>LimitTracker)]
    R -->|RouterListener| U[UsageService<br/>usage]
    U --> DB[(PostgreSQL<br/>usage_record)]
    U -->|UsageRecordedEvent| B[StatusBroadcaster<br/>status]
    P[OpenRouterKeyPoller<br/>@Scheduled] --> OR
    ST[StatusService] --> DB
    ST --> S
    B --> ST
    B -->|SSE /status/stream| D[Дашборд /]
    ST -->|GET /status| T[curl / будущий Telegram-бот]
```

Путь запроса: `ChatController` передаёт запрос в `ProviderRouter`. Тот выбирает первого здорового провайдера по приоритету
и вызывает `OpenAiCompatibleProvider` (WebClient). Если провайдер отвечает ошибкой, роутер ставит ему cooldown
и переходит к следующему. Результат каждой попытки уходит в `UsageService`: запись в БД и событие для дашборда.

В режиме стриминга failover возможен **только до первого чанка**. Почему так, объяснено в
[docs/webflux-notes.md](docs/webflux-notes.md). Это учебный конспект по WebClient, Mono/Flux, backpressure и SSE.

## Быстрый старт

Нужен ключ хотя бы одного провайдера. Бесплатный ключ Groq: https://console.groq.com/keys

### Вариант 1: Docker Compose (шлюз + PostgreSQL 16)

```bash
cp .env.example .env            # вписать GROQ_API_KEY=...
docker compose up -d --build    # Flyway накатит миграции при старте шлюза
docker compose ps               # оба сервиса healthy
curl localhost:4000/actuator/health
open http://localhost:4000/     # дашборд
docker compose down             # остановить (down -v — удалить и данные БД)
```

Если в вашей среде контейнеры в bridge-сети не видят друг друга, используйте host-сеть:
`docker compose -f docker-compose.yml -f docker-compose.hostnet.yml up -d --build`.

### Вариант 2: локально (JDK 21), база в Docker

```bash
docker compose up -d postgres
export GROQ_API_KEY=gsk_...
./mvnw spring-boot:run          # http://localhost:4000
```

## Дашборд: здоровье, модели, квоты

На `/` для каждого провайдера есть кнопка **Ping** и общая кнопка **«Пинговать всех»**.

| Метод | Путь | Что делает |
|---|---|---|
| `POST` | `/status/providers/{name}/ping` | `GET {base-url}/models` с таймаутом → `state` (`up`/`down`/`skipped`), `latency_ms`, `http_status`, `error`, `models_count` |
| `POST` | `/status/providers/ping` | то же для всех провайдеров параллельно |
| `GET` | `/status/providers/{name}/models[?refresh=true]` | модели провайдера из его `/models` (кэш `models-ttl`) |
| `GET` | `/status/models[?refresh=true]` | `gateway` — что отдаёт `/v1/models`, `providers` — модели каждого провайдера |

Ping не тратит токены и не учитывается в RPM/RPD шлюза. Результат последнего ping есть в `GET /status` (`last_ping`).
Ping по расписанию: `gateway.health.scheduled=true` (интервал `gateway.health.interval`, по умолчанию 5 минут).

**Квота — только то, что реально известно:**

- RPM/RPD шлюза — собственные счётчики `LimitTracker` против `limits` из конфига;
- `remaining_requests` / `remaining_tokens`, `reset_requests_at` / `reset_tokens_at` — из заголовков
  `x-ratelimit-*` последнего ответа (Groq присылает всё; OpenRouter — `X-RateLimit-Remaining/Reset`);
  `limits_seen_at` — когда заголовки пришли в последний раз;
- токены за сегодня — сумма по `usage_record`;
- OpenRouter — остаток кредитов ключа (`GET /api/v1/key`).

Если провайдер чего-то не сообщает (например, Gemini не шлёт `x-ratelimit-*`), поле в JSON отсутствует,
а дашборд пишет **«неизвестно»**. Числа не выдумываются.

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `GROQ_API_KEY` | — | ключ Groq (пусто → провайдер пропускается) |
| `GEMINI_API_KEY` | — | ключ Google AI Studio (OpenAI-совместимый endpoint) |
| `OPENROUTER_API_KEY` | — | ключ OpenRouter; включает опрос `/api/v1/key` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gateway` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `gateway` / `gateway` | учётные данные БД |
| `DB_NAME`, `DB_PORT`, `GATEWAY_PORT` | `gateway`, `5432`, `4000` | только для docker compose |

## Конфигурация провайдеров

`src/main/resources/application.yml`:

```yaml
gateway:
  default-cooldown: 5m            # пауза после 429/5xx без Retry-After
  openrouter:
    name: openrouter              # какой провайдер опрашивать через GET /key
    poll-interval: PT5M           # ISO-8601
  status:
    heartbeat: PT15S              # SSE heartbeat
    state-check: PT1S             # проверка смены состояния провайдеров
    emitter-timeout: 30m
  providers:
    - name: groq
      base-url: https://api.groq.com/openai/v1
      api-key: ${GROQ_API_KEY:}
      model: openai/gpt-oss-120b
      priority: 1                 # меньше = раньше
      timeout: 120s               # non-stream: на весь ответ; stream: пауза между чанками
      stream-usage: true          # слать stream_options.include_usage
      limits:
        rpm: 30                   # собственные лимиты шлюза (0/нет = без лимита)
        rpd: 1000
```

| Поле | Обязательно | Описание |
|---|---|---|
| `name` | да | имя в логах, `/status`, `usage_record` |
| `base-url` | да | OpenAI-совместимый префикс (без `/chat/completions`) |
| `api-key` | нет | пустой → провайдер пропускается (`skipped`) |
| `model` | да | реальная модель провайдера (подменяет модель клиента) |
| `priority` | нет (100) | порядок перебора |
| `timeout` | нет (120s) | таймаут запроса |
| `limits.rpm` / `limits.rpd` | нет | лимиты запросов в минуту/сутки |
| `stream-usage` | нет (true) | запрашивать usage в стриме |

Провайдеры из коробки:

| Провайдер | base-url | Модель по умолчанию | priority |
|---|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b` | 1 |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.5-flash` | 2 |
| OpenRouter | `https://openrouter.ai/api/v1` | `meta-llama/llama-3.3-70b-instruct:free` | 3 |

Подойдёт любой OpenAI-совместимый сервер (OpenAI, Together, vLLM, Ollama `http://localhost:11434/v1` и т. д.):
достаточно добавить элемент в список.

Поддерживаемые заголовки лимитов:

| Заголовок | Кто шлёт | Что делаем |
|---|---|---|
| `x-ratelimit-remaining-requests`, `x-ratelimit-remaining-tokens` | Groq, OpenAI | сохраняем; requests = 0 → пауза |
| `x-ratelimit-reset-requests` (`2m59.56s`) | Groq, OpenAI | длительность паузы при requests = 0 |
| `X-RateLimit-Remaining`, `X-RateLimit-Reset` (epoch ms) | OpenRouter | то же |
| `Retry-After` (секунды или HTTP-дата) | все | длительность cooldown после 429/5xx |

## Подключение клиентов

Шлюзу ключ от клиента не нужен, но многие клиенты требуют непустой ключ. Передайте любой, например `dummy`.

### curl

```bash
curl -s localhost:4000/v1/models

curl -s localhost:4000/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"openai/coder","messages":[{"role":"user","content":"Скажи привет"}]}'

# стриминг
curl -N localhost:4000/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"openai/coder","stream":true,"messages":[{"role":"user","content":"Перечисли три цвета радуги"}]}'
```

### Aider

```bash
aider --openai-api-base http://localhost:4000/v1 --openai-api-key dummy --model openai/coder
# без стриминга: добавить --no-stream
```

### OpenHands

В настройках LLM (Settings → LLM → Advanced) или через переменные окружения:

```bash
LLM_MODEL=openai/coder
LLM_BASE_URL=http://host.docker.internal:4000/v1   # OpenHands в Docker; локально — http://localhost:4000/v1
LLM_API_KEY=dummy
```

### OpenAI SDK (Python)

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:4000/v1", api_key="dummy")
print(client.chat.completions.create(model="openai/coder",
      messages=[{"role": "user", "content": "Привет"}]).choices[0].message.content)
```

## Дашборд, `/status` и SSE

- **`http://localhost:4000/`**: карточки провайдеров (работает / cooldown / лимит / пропущен) с RPM/RPD и
  остатками из заголовков, итоги дня, график запросов за 24 часа по часам, последние 20 запросов.
- **`GET /status`**: тот же срез в стабильном snake_case JSON (для curl и будущего Telegram-бота):
  ```json
  {"generated_at":"...","providers":[{"name":"groq","state":"healthy","remaining_requests":996,
    "rpm_used":1,"rpm_limit":30,"requests_today":4,"tokens_today":580,"errors_today":0,...}],
   "today":{"requests":4,"tokens":580,"errors":0},"hourly":[...24 шт...],"recent":[...]}
  ```
- **`GET /status/stream`**: `text/event-stream`. Событие `snapshot` (полный JSON как в `/status`) приходит при
  подключении, после каждого записанного запроса и при смене состояния провайдера (начало/конец cooldown).
  Событие `heartbeat` приходит раз в 15 с. `retry: 3000` задаёт задержку автопереподключения `EventSource`.
  ```bash
  curl -N localhost:4000/status/stream
  ```
- **`GET /actuator/health`**: healthcheck.

## Тесты

```bash
./mvnw test     # быстрые unit/slice-тесты на H2 (≈40 шт., Docker не нужен)
./mvnw verify   # + интеграционные *IT: Testcontainers PostgreSQL 16 + WireMock (нужен Docker)
```

Интеграционные тесты (`GatewayIT`) поднимают шлюз на случайном порту, настоящий PostgreSQL 16 с миграцией
Flyway и двух провайдеров на WireMock. Они проверяют сквозные запросы без стриминга и со стримингом,
failover 429 → второй провайдер, записи в `usage_record` и содержимое `/status`. Реальные API-ключи не нужны.

## Структура проекта

```
src/main/java/ru/akiev/gateway/
├── api/        ChatController (/v1/*), ApiExceptionHandler (ошибки в формате OpenAI)
├── config/     GatewayProperties, ProviderProperties, ProviderConfig (сборка провайдеров и роутера)
├── dto/        ChatRequest/Response, Message, Choice, Usage, ModelList, ErrorResponse
├── provider/   LlmProvider, OpenAiCompatibleProvider (WebClient), ProviderState, RateLimitHeaders
├── router/     ProviderRouter (приоритет + failover), StreamSession (стриминг), RouterListener
├── usage/      UsageRecord (JPA), UsageService, LimitTracker (RPM/RPD)
├── status/     StatusService, StatusController (/status, /status/stream), StatusBroadcaster (SSE),
│               OpenRouterKeyPoller
└── telegram/   зарезервировано (бот отложен)
src/main/resources/
├── application.yml
├── db/migration/V1__usage_record.sql
└── static/     index.html, app.js, style.css (дашборд)
docs/webflux-notes.md   учебный конспект по WebClient / Reactor / SSE
Dockerfile, docker-compose.yml, docker-compose.hostnet.yml, .env.example
```

## Этапы и roadmap

| Этап | Что сделано | Issues / PR |
|---|---|---|
| 1 | прокси MVP: DTO, WebClient-провайдер, `/v1/chat/completions`, `/v1/models` | #1–#5, PR #20 |
| 2 | ротация провайдеров, cooldown, 503, заголовки лимитов | #6–#9, PR #21 |
| 3 | `usage_record` + Flyway, LimitTracker, `/status`, OpenRouter `/key`, SSE-дашборд | #10–#13, PR #22 |
| 4 | стриминг, Docker Compose, интеграционные тесты, README | #15, #17–#19, PR #23 и этот |

Дальше:
- [ ] **Telegram-бот** (`/status`, алерты при приближении к лимитам) — **отложен** по решению владельца (#14).
- [ ] Полный переход на WebFlux (`Flux<ServerSentEvent>` вместо моста `toStream`), см. `docs/webflux-notes.md`.
- [ ] Учёт лимитов по токенам (TPM/TPD), выбор провайдера с учётом остатка токенов.
- [ ] Простая авторизация клиентов шлюза (собственный API-ключ).
- ~~Кэш ответов (#16)~~: не нужен.
