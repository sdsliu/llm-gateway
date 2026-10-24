# llm-gateway

Личный **OpenAI-совместимый LLM-шлюз** на Spring Boot 3 / Java 21. Он принимает запросы в формате
OpenAI Chat Completions и отправляет их бесплатным или дешёвым провайдерам (Groq, Z.ai, Kilo, OVHcloud, Cloudflare Workers AI, OpenRouter, SambaNova и др.).
Провайдеры перебираются по приоритету, а при исчерпании лимитов шлюз автоматически переключается на следующего.
Каждый запрос записывается в PostgreSQL, а состояние видно на живом веб-дашборде.

Клиенты (Aider, OpenHands, OpenAI SDK, curl) указывают `http://localhost:4000/v1` как base URL
и ничего не знают о том, какой провайдер на самом деле ответил.

## Возможности

- `POST /v1/chat/completions` и `GET /v1/models` в формате OpenAI. Неизвестные поля игнорируются.
- **Стриминг** `stream: true`: SSE в формате OpenAI (`data: {chunk}` … `data: [DONE]`).
- **Ротация провайдеров** по `priority`. При 429, 5xx, 401/402/403 и таймаутах провайдер уходит на паузу
  (`Retry-After` или 5 минут), а запрос повторяется у следующего. Если недоступны все — `503` в формате OpenAI.
- **Маршрутизация по лимитам**: RPM, RPD, TPM, токены в сутки и в месяц. Лимиты шлюз узнаёт у самих провайдеров
  при старте (до приёма запросов) и из заголовков ответов; конфиг — запасной вариант. Не ответившие провайдеры
  исключаются из роутинга и перепроверяются раз в минуту. Подробно — «Лимиты провайдеров» ниже.
- **Подмена модели**: клиент шлёт любое имя (например `openai/coder`), провайдеру уходит модель из конфига.
- **Учёт usage**: таблица `usage_record` (Flyway). В неё попадает каждая попытка: провайдер, модель, токены,
  статус и задержка.
- **Дашборд** на `/`, обновляется в реальном времени через SSE. Есть также JSON `GET /status`.
- **Режим роутинга** переключается с дашборда: по запасу лимитов (по умолчанию), по приоритету (с выбором primary)
  или round-robin.
- **OpenRouter**: остаток кредитов ключа проверяется по расписанию (`GET /api/v1/key`).
- **Docker Compose**: шлюз + PostgreSQL 16 с healthcheck.

## Архитектура

```mermaid
flowchart LR
    C[Клиенты<br/>Aider · OpenHands · curl · OpenAI SDK] -->|POST /v1/chat/completions| API[ChatController<br/>api]
    API --> R[ProviderRouter<br/>router]
    R -->|priority 1| G[Groq]
    R -->|priority 2| Z[Z.ai GLM]
    R -->|priority 3| K[Kilo Gateway<br/>без ключа]
    R -->|priority 4| OVH[OVHcloud AI Endpoints<br/>без ключа]
    R -->|priority 5| CF[Cloudflare Workers AI]
    R -->|priority 6| OR[OpenRouter]
    R -->|priority 7| SN[SambaNova]
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

Ключи необязательны: Kilo Gateway и OVHcloud AI Endpoints работают анонимно (keyless), с небольшими лимитами на IP.
Для нормальной работы задайте хотя бы один ключ. Бесплатный ключ Groq: https://console.groq.com/keys

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

Если провайдер чего-то не сообщает (например, Z.ai не документирует заголовки `x-ratelimit-*`), поле в JSON отсутствует,
а дашборд пишет **«неизвестно»**. Числа не выдумываются.

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `GROQ_API_KEY` | — | ключ Groq (пусто → провайдер пропускается) |
| `SAMBANOVA_API_KEY` | — | ключ SambaNova Cloud, https://cloud.sambanova.ai/apis |
| `ZAI_API_KEY` | — | ключ Z.ai (Zhipu GLM), https://z.ai/manage-apikey/apikey-list |
| `OPENROUTER_API_KEY` | — | ключ OpenRouter; включает опрос `/api/v1/key` |
| `KILO_API_KEY` | — | необязательный ключ Kilo AI Gateway; без него — анонимно (free-модели, 200 запросов/час на IP) |
| `OVH_AI_API_KEY` | — | необязательный ключ OVHcloud AI Endpoints; без него — анонимно (2 запроса/мин на IP и модель) |
| `OVH_AI_RPM` | `2` | RPM шлюза для OVHcloud; с ключом поставьте `400` |
| `CLOUDFLARE_API_TOKEN` | — | токен Cloudflare с правами Workers AI (пусто → провайдер пропускается) |
| `CLOUDFLARE_ACCOUNT_ID` | — | Account ID Cloudflare, подставляется в base-url |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gateway` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `gateway` / `gateway` | учётные данные БД |
| `DB_NAME`, `DB_PORT`, `GATEWAY_PORT` | `gateway`, `5432`, `4000` | только для docker compose |

## Конфигурация провайдеров

`src/main/resources/application.yml`:

```yaml
gateway:
  default-cooldown: 5m            # пауза после 429/5xx/402/таймаута без Retry-After (и при Retry-After: 0)
  model-unavailable-cooldown: 10m # 404 «model unavailable» — не звать участника в этой группе
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
      timeout: 30s                # non-stream: на весь ответ; stream: пауза между чанками
      stream-usage: true          # слать stream_options.include_usage
      limits:
        rpm: 30                   # собственные лимиты шлюза (0/нет = без лимита)
        rpd: 1000
```

| Поле | Обязательно | Описание |
|---|---|---|
| `name` | да | имя в логах, `/status`, `usage_record` |
| `base-url` | да | OpenAI-совместимый префикс (без `/chat/completions`) |
| `api-key` | нет | пустой → провайдер пропускается (`skipped`), если не задан `api-key-optional` |
| `api-key-optional` | нет (false) | `true` — keyless-провайдер: при пустом `api-key` не пропускается, запросы идут без `Authorization` |
| `model` | да | реальная модель провайдера (подменяет модель клиента) |
| `priority` | нет (100) | порядок перебора |
| `timeout` | нет (30s) | таймаут запроса к этому провайдеру (non-stream — весь ответ, stream — до/между чанками); истёк → 504, cooldown и failover. Держите его заметно ниже read-timeout клиента (у Nori это ~120 с): иначе зависший участник группы съедает весь бюджет, и failover приходит слишком поздно. У groq / openrouter / sambanova в конфиге 30 с |
| `extra-body` | нет | доп. поля верхнего уровня в теле `/chat/completions` только для этого провайдера (например Z.ai `thinking: {type: disabled}`); `model`, `messages`, `stream`, `stream_options`, `models` не переопределяются |
| `fallback-models` | нет | запасные модели OpenRouter (поле `models`) |
| `limits.rpm` / `limits.rpd` | нет | лимиты запросов в минуту/сутки |
| `max-concurrency` | нет | сколько запросов держать в полёте; сверх этого провайдер пропускается (всплеск уходит другим). `0`/пусто — без ограничения |
| `stream-usage` | нет (true) | запрашивать usage в стриме |
| `ping-path` | нет (`/models`) | путь ping дашборда; 404/405 = «доступен, список моделей не поддерживается» |

Провайдеры из коробки:

| Провайдер | base-url | Модель по умолчанию | priority |
|---|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b` | 1 |
| Z.ai (GLM) | `https://api.z.ai/api/paas/v4` | `glm-4.7-flash` (бесплатная; thinking выключен, timeout 20s) | 2 |
| Kilo AI Gateway | `https://ai-gateway.kilo.ai/api/v1` | `nvidia/nemotron-3-super-120b-a12b:free` (без ключа: 200 запросов/час на IP; шлюз: 10 RPM / 4800 RPD) | 3 |
| OVHcloud AI Endpoints | `https://oai.endpoints.kepler.ai.cloud.ovh.net/v1` | `gpt-oss-120b` (без ключа: 2 RPM на IP и модель; с ключом 400 RPM) | 4 |
| Cloudflare Workers AI | `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1` | `@cf/qwen/qwen3-30b-a3b-fp8` (free: 10 000 Neurons/сутки; 300 RPM) | 5 |
| OpenRouter | `https://openrouter.ai/api/v1` | `nvidia/nemotron-3-super-120b-a12b:free` | 6 |
| SambaNova Cloud | `https://api.sambanova.ai/v1` | `gpt-oss-120b` (Free Tier: 20 RPM / 20 RPD / 200K TPD на модель; без карты — 402) | 7 |

> Kilo AI Gateway ([docs](https://kilo.ai/docs/gateway), [Authentication](https://kilo.ai/docs/gateway/authentication)): без ключа доступны только бесплатные модели (`:free`), лимит — 200 запросов в час на IP. `api-key-optional: true`: без `KILO_API_KEY` запросы уходят без заголовка `Authorization`. Модель — `nvidia/nemotron-3-super-120b-a12b:free` (стабильно отвечает без ключа). `kilo-auto/free` не используем: он уводит запрос на случайную free-модель, часть которых тратит весь `max_tokens` на reasoning и возвращает `content: null`. Список моделей: `curl https://ai-gateway.kilo.ai/api/v1/models` (бесплатные — с `:free`).

> OVHcloud AI Endpoints ([Getting started](https://docs.ovhcloud.com/en/guides/public-cloud/ai-machine-learning/ai-endpoints-getting-started)): анонимно — 2 запроса в минуту на IP и на модель, с ключом — 400 в минуту на проект и модель (429 при превышении). OVH присылает `ratelimit-remaining` / `ratelimit-reset`: при остатке 0 шлюз сам ставит паузу до сброса окна. Каталог моделей: `curl https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/models` (без ключа).

> Cloudflare Workers AI ([OpenAI compatibility](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/), [Pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), [Limits](https://developers.cloudflare.com/workers-ai/platform/limits/)): нужны `CLOUDFLARE_API_TOKEN` и `CLOUDFLARE_ACCOUNT_ID`. Бесплатно — 10 000 Neurons в сутки; `@cf/qwen/qwen3-30b-a3b-fp8` стоит 4625 neurons / M входных и 30475 / M выходных токенов (≈ 330k выходных токенов в сутки, у `llama-3.3-70b-instruct-fp8-fast` — ≈ 49k). Суточная квота считается в нейронах, поэтому RPD шлюза не задан: исчерпание квоты = ошибка Cloudflare → cooldown и failover. OpenAI-совместимого `GET /models` нет (405) — ping считает такой ответ «доступен».

> SambaNova: OpenAI-совместимый API, `Authorization: Bearer <SAMBANOVA_API_KEY>` ([API keys and URLs](https://docs.sambanova.ai/docs/en/get-started/api-keys-urls), [OpenAI compatibility](https://docs.sambanova.ai/docs/en/features/openai-compatibility)). Лимиты Free Tier по [Rate limits](https://docs.sambanova.ai/docs/en/models/rate-limits): 20 RPM / 20 RPD / 200K токенов в день на каждую модель; заголовки `x-ratelimit-*-requests` (минута) и `x-ratelimit-*-requests-day` (сутки). **Важно:** с августа 2026 SambaCloud без привязанной карты отвечает `402 PAYMENT_METHOD_REQUIRED` (проверено 2026-10-17 на всех моделях из `/v1/models`). Шлюз считает 402 поводом для failover: SambaNova получает cooldown, запрос уходит дальше.
>
> Z.ai: у GLM-4.7 «размышление» включено по умолчанию — простой запрос тратил ~1600 completion-токенов и 36–77 с. В конфиге `extra-body.thinking.type: disabled` ([Thinking Mode](https://docs.z.ai/guides/capabilities/thinking-mode), [Chat Completion](https://docs.z.ai/api-reference/llm/chat-completion)): reasoning-токенов 0. Задержка бесплатной `glm-4.7-flash` всё равно зависит от загрузки Z.ai (бывает 429 code 1305 «overloaded»), поэтому `timeout: 20s`: дольше ждать нет смысла, клиенты вроде path-code-ai отваливаются по read-timeout 30s.
>
> OpenRouter: `fallback-models` в конфиге провайдера уходит в тело запроса как нативный параметр OpenRouter `models` ([Model Fallbacks](https://openrouter.ai/docs/guides/routing/model-fallbacks)): если основная модель недоступна или упёрлась в лимит, OpenRouter сам пробует следующие по порядку (сейчас `google/gemma-4-31b-it:free` → `poolside/laguna-s-2.1:free` → `openrouter/free`). Задавайте `fallback-models` только провайдерам, которые понимают поле `models`. Если OpenRouter всё же отвечает 404 «No endpoints found», шлюз переключается на следующего провайдера (как при 429/5xx).
>
> OpenRouter: бесплатные модели периодически снимают — тогда OpenRouter отвечает 404 «No endpoints found». Актуальный список: `curl https://openrouter.ai/api/v1/models` (id с `:free`). Тот же 404 бывает, если в https://openrouter.ai/settings/privacy запрещены провайдеры, обучающиеся на запросах (free-модели их требуют).

Подойдёт любой OpenAI-совместимый сервер (OpenAI, Together, vLLM, Ollama `http://localhost:11434/v1` и т. д.):
достаточно добавить элемент в список.

Поддерживаемые заголовки лимитов:

| Заголовок | Кто шлёт | Что делаем |
|---|---|---|
| `x-ratelimit-remaining-requests`, `x-ratelimit-remaining-tokens` | Groq, OpenAI | сохраняем; requests = 0 → пауза |
| `x-ratelimit-reset-requests` (`2m59.56s`) | Groq, OpenAI | длительность паузы при requests = 0 |
| `X-RateLimit-Remaining`, `X-RateLimit-Reset` (epoch ms) | OpenRouter | то же |
| `ratelimit-remaining`, `ratelimit-reset` (секунды), `x-ratelimit-remaining-minute` | OVHcloud (IETF draft) | то же |
| `Retry-After` (секунды или HTTP-дата) | все | длительность cooldown после 429/5xx |

## Подключение клиентов

Шлюзу ключ от клиента не нужен, но многие клиенты требуют непустой ключ. Передайте любой, например `dummy`.

### curl

```bash
curl -s localhost:4000/v1/models

curl -s localhost:4000/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"gpt-oss-120b","messages":[{"role":"user","content":"Скажи привет"}]}'
# то же самое: алиас группы, запрос разъедется по Groq / OVH / SambaNova / OpenRouter
# -d '{"model":"openai/gpt-oss-120b",...}'

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

## Эффективность: учёт по прогонам (`/stats/runs`, `/stats/efficiency`)

Клиенты помечают запросы заголовками — шлюз сохраняет их в `usage_record`
(миграция `V2__usage_record_tags.sql`, колонки `run_id`, `issue`, `client`, `request_user`):

| Заголовок / поле | Кто шлёт | Пример |
|---|---|---|
| `x-run-id` | ai-runner, project-sage | `20261017-101500-42` |
| `x-issue` | ai-runner, project-sage | `42` |
| `x-client` | ai-runner, project-sage | `ai-runner` |
| поле `user` в теле | любой OpenAI-клиент | `project-sage` |

Все метки необязательны; без них запрос учитывается как раньше (группа «(без метки)»).

```bash
# агрегаты: запросы, prompt/completion токены, провайдеры, первый/последний запрос, ошибки, оценка стоимости
curl -s 'localhost:4000/stats/runs?groupBy=run&days=30&limit=200'   # groupBy = run | issue | client
# склейка с итогами ai-runner (успех, попытки, PR, категория ошибки)
curl -s 'localhost:4000/stats/efficiency?days=30'
```

Страница **«Эффективность»** — `http://localhost:4000/efficiency.html` (ссылка в шапке дашборда):
доля успешных прогонов, токены на один успешный прогон, оценка стоимости, таблица прогонов и группировки.

**Как склеиваются данные.** Выбран простой вариант: шлюз сам делает GET к `ai-runner` `/stats`
(`RUNNER_STATS_URL`, по умолчанию `http://localhost:8085/stats`) и соединяет по `run_id`.
Зависимость односторонняя (шлюз → runner), runner о шлюзе ничего не знает, новых таблиц и
синхронизации нет. Если runner не запущен — страница работает, показывая только данные шлюза
(`runner_available: false`). Прогоны без итога runner (например, project-sage) попадают в `other`.

**Стоимость — это ОЦЕНКА.** Бесплатные провайдеры ничего не стоят; таблица `gateway.pricing.models`
в `application.yml` показывает, «сколько стоили бы те же токены» на платной модели (цена за 1M
входных/выходных токенов). Цены приблизительные — проверяйте у провайдера.

## Лимиты провайдеров (RPM, TPM, суточные и месячные квоты)

Бесплатные провайдеры ограничены по-разному: у Groq 8000 токенов в минуту, у Gonka 6 запросов в минуту и ~1M токенов
в месяц, у SambaNova 20 запросов в минуту и 200K токенов в сутки. Шлюз считает все эти лимиты и отправляет запрос
туда, где под него есть место.

**1. Опрос при старте — до приёма запросов.** Все провайдеры опрашиваются параллельно, у каждого свой таймаут
(`gateway.limits.probe-timeout`, 10 с), поэтому старт не зависает. Способ опроса выбирается по `base-url`
(или явно, параметром `limits-probe` у провайдера):

| Провайдер | Что делаем (`limits-probe`) | Что узнаём |
|---|---|---|
| Groq | `groq`: `GET /models`, затем крошечный `chat` (`max_tokens: 1`) | `x-ratelimit-limit-tokens` → TPM, `x-ratelimit-limit-requests` → **RPD** (у Groq это запросы в сутки) |
| OpenRouter | `openrouter`: `GET /api/v1/key` | `rate_limit` → RPM (если указан); для `:free`-моделей `is_free_tier` → RPD 50 / 1000 |
| Gonka Broker | `gonka`: `GET /test-auth` (адрес — `limits-probe-url`) | `maxRpm` → RPM (а также TPM и месячная квота, если они есть в ответе) |
| остальные | `models`: `GET {ping-path}` | лимиты, если провайдер присылает `x-ratelimit-limit-*` |
| — | `none` | не опрашивать: провайдер всегда в роутинге, лимиты из конфига |

Не ответил (таймаут, 5xx, 401/403, 429) — **провайдер исключается из роутинга**: `WARN … ИСКЛЮЧЁН из роутинга` в логе,
состояние «исключён» на дашборде. Раз в `gateway.limits.reprobe-interval` (минута) исключённые опрашиваются снова и
возвращаются в роутинг, как только ответят. Лимиты из API у работающих обновляются раз в `refresh-interval`.

**2. Источник лимита.** Действует первое известное значение из списка: заголовки ответа (`headers`), затем API провайдера
(`api`), затем `limits` в `application.yml` (`config`). Источник каждого лимита виден в логе и на дашборде:

```
Провайдер groq: доступен (GET /models + POST /chat/completions (max_tokens=1) → HTTP 200, 412 мс).
Лимиты: rpm=30 (config), rpd=1000 (headers), tpm=8000 (headers), tokensPerDay=—, tokensPerMonth=—
```

```yaml
- name: sambanova
  limits:                  # fallback, если провайдер ничего не сообщил
    rpm: 20
    rpd: 20
    tpm: 100000            # токенов в минуту
    tokens-per-day: 200000 # сутки по UTC
    tokens-per-month: 1000000
  models: [gpt-oss-120b, Llama-4-Maverick-17B-128E-Instruct]   # дополнительные модели (кроме model)
  limits-probe: auto       # auto | groq | openrouter | gonka | models | none
```

**3. Счётчики.** Запросы и токены считаются по каждому провайдеру: за скользящую минуту (в памяти) и за сутки и месяц
по UTC. Суточные и месячные счётчики при старте восстанавливаются из `usage_record`, поэтому переживают рестарт.

**4. Оценка запроса — до отправки**: вход (~4 символа на токен плюс 4 токена на сообщение) + `max_tokens`. Если клиент
не прислал `max_tokens`, берётся `gateway.limits.default-max-tokens` (1024). Оценка резервируется у выбранного
провайдера сразу, поэтому параллельные запросы не займут один и тот же остаток. После ответа резерв заменяется
реальным `usage`, а остаток уточняется по `x-ratelimit-remaining-tokens`.

**5. Выбор.** Кандидаты — участники группы моделей (см. ниже), если клиент прислал её имя или алиас; иначе провайдеры,
у которых есть запрошенная модель (`model` + `models`; если модели нет ни у кого — все, как раньше). Провайдер без места
по **любому** лимиту пропускается. В режиме `HEADROOM` (по умолчанию) счёт = запас минус число запросов уже в полёте
(запас — минимум по всем лимитам величины (остаток − запрос) / лимит). Поэтому параллельный всплеск расходится по
разным провайдерам, а не занимает один и тот же максимум. При равенстве решает `priority`. `max-concurrency` — жёсткий
потолок слотов в полёте. В режимах `PRIORITY` и `ROUND_ROBIN` порядок прежний (in-flight на счёт не влияет), но
провайдер без места или с занятым `max-concurrency` пропускается.

**6. Мест нет.**
- Ни у кого нет места в эту минуту: запрос ждёт освобождения до `gateway.limits.max-wait` (20 с), потом получает
  `429 rate_limit_exceeded` с `Retry-After`.
- Запрос больше TPM **всех** провайдеров: сразу `413 request_too_large`, ждать бесполезно.

**7. 429 от провайдера**: провайдер встаёт на паузу (до положительного `Retry-After`, иначе `x-ratelimit-reset-*`,
иначе `default-cooldown`) и **пропускается**, пока пауза не кончится. `Retry-After: 0` не считается «можно сразу»:
это тот же `default-cooldown`. Успешный ping (`GET /models` часто 200, пока chat ещё в лимите — так у SambaNova)
паузу **не снимает**. Следующий запрос к группе идёт к другим участникам, а не снова в 429.

**8. Одна модель на попытку** (`gateway.limits.pin-model-per-run: true`): все запросы с одним `x-run-id` уходят к
одному провайдеру, чтобы агент не менял модель посреди задачи. Если у этого провайдера кончилась минута, запрос ждёт.
Если провайдер недоступен надолго (пауза, исключён, суточная квота), run переключается на другого провайдера с
`WARN` в логе.

```yaml
gateway:
  limits:
    startup-probe: true      # false — без опросов (так в тестах): все провайдеры в роутинге, лимиты из конфига
    probe-timeout: PT10S
    reprobe-interval: PT1M
    refresh-interval: PT30M
    max-wait: PT20S
    default-max-tokens: 1024
    pin-model-per-run: false
    pin-ttl: PT12H
```

**Дашборд и `/status`.** У каждого провайдера есть блок «Лимиты (осталось)»: для каждого лимита показано использовано,
лимит, остаток и источник, а также итог последнего опроса. В JSON это `providers[].limits.{rpm,rpd,tpm,tokens_per_day,tokens_per_month}`
с полями `{limit, used, remaining, source}`, а также `excluded`, `excluded_reason`, `excluded_since` и `limits_probe`.

## Режим роутинга (headroom / priority / round-robin)

| Режим | Порядок перебора |
|---|---|
| `HEADROOM` (по умолчанию в `application.yml`) | к провайдеру с наибольшим запасом по лимитам **минус запросы в полёте**; при равенстве — по `priority`; `primary` игнорируется |
| `PRIORITY` | по `priority`; если задан `primary`, он идёт первым, остальные — по `priority` |
| `ROUND_ROBIN` | по кругу среди **доступных сейчас** провайдеров (cooldown/лимит пропускаются, без ключа их и так нет); `primary` игнорируется |

Во всех режимах failover одинаковый: при 429/5xx/таймауте провайдер уходит в cooldown, запрос идёт к следующему
в текущем порядке. 429 уважает `Retry-After` этого провайдера. Таймаут — свой у каждого провайдера
(`gateway.providers[].timeout`, по умолчанию 30 с): истёкший даёт 504 и failover, не дожидаясь клиентского
read-timeout. Внутри группы моделей (и для модели `auto`) то же
делается для 404/401/403/402: провайдер остывает, пробуем следующего участника; клиент получает 429 только если
429 ответила вся группа. Обычный 404 вне группы по-прежнему сразу уходит клиенту. Счётчик round-robin —
`AtomicLong`, параллельные запросы распределяются равномерно.

Значение по умолчанию — в `application.yml`:

```yaml
gateway:
  routing:
    mode: headroom      # headroom | priority | round-robin
    primary:            # имя провайдера; пусто — чистый приоритет
```

Опечатка в `primary` (такого провайдера нет в `gateway.providers`) — ошибка старта; провайдер без api-key
или с битым `base-url` — предупреждение в логе и работа по чистому приоритету.

Смена в runtime — блок **«Режим роутинга»** на дашборде (радио-кнопки режима + выпадающий список primary;
применяется сразу) или REST:

```bash
curl localhost:4000/status/routing
# {"mode":"PRIORITY","primary":null,"providers":["groq","sambanova","zai","openrouter"],"order":["groq","sambanova","zai","openrouter"]}
curl -X PUT localhost:4000/status/routing -H 'Content-Type: application/json' \
     -d '{"mode":"PRIORITY","primary":"openrouter"}'
curl -X PUT localhost:4000/status/routing -H 'Content-Type: application/json' -d '{"mode":"ROUND_ROBIN"}'
curl -X DELETE localhost:4000/status/routing   # сбросить к gateway.routing из application.yml
```

- `mode` обязателен (`HEADROOM` / `PRIORITY` / `ROUND_ROBIN`, регистр и `-`/`_` не важны); `primary` — имя включённого
  провайдера (с api-key) или `null`/`""`. Иначе `400` в формате OpenAI (`{"error":{"message":...}}`) и режим не меняется.
- `order` — порядок, в котором будет перебирать провайдеров следующий запрос.
- Тот же объект приходит полем `routing` в `GET /status` и в SSE-событии `snapshot`: после `PUT` все открытые
  дашборды обновляются сразу. Смена режима пишется в лог на уровне INFO.
- `options` — все настроенные провайдеры (по priority) с живым состоянием: `state` = `healthy` | `cooldown` | `limit` |
  `excluded` | `no_key` | `invalid`, `until` — до какого момента недоступен, `reason` — причина паузы, `selectable` — можно ли выбрать primary.
  Провайдер в cooldown / с исчерпанным лимитом **остаётся выбираемым**: выбор сохраняется, пока primary недоступен,
  запросы идут к следующему по priority, а после конца паузы/сброса лимита — снова к primary (порядок
  пересчитывается на каждый запрос). Нельзя выбрать только провайдера без api-key (на дашборде он виден, но disabled
  с подписью «нет ключа»).
- **Выбор сохраняется в БД** (таблица `routing_settings`, одна строка, миграция Flyway `V3__routing_settings.sql`) и
  переживает рестарт: при старте шлюз берёт значение из БД, если оно есть, иначе `gateway.routing` из `application.yml`
  (поле `source`: `DB` / `CONFIG`). Если сохранённый primary больше не годится (провайдер убран из конфига или у него
  нет api-key) — WARN в логе, `warning` в ответе и порядок из конфига; строка в БД при этом не удаляется — вернётся
  ключ, вернётся и выбор. `DELETE /status/routing` (кнопка «Сбросить к настройкам из конфига») удаляет сохранённое.
- **Авторизации нет** (как и у всего `/status` и дашборда): `PUT /status/routing` может вызвать любой, кто достучится
  до порта. Запускайте шлюз локально / в доверенной сети и не публикуйте порт 4000 наружу.

## Группы моделей

Один логический id на несколько провайдеров. У каждого участника свой model id: шлюз подставляет его в тело
запроса. Так клиент, который шлёт `openai/gpt-oss-120b`, не застревает на одном Groq.

```yaml
gateway:
  model-groups:
    - name: gpt-oss-120b          # это имя (или алиас) шлёт клиент, в том числе Nori
      aliases:
        - openai/gpt-oss-120b     # тот же id, что у Groq — тоже попадает в группу, а не только на Groq
      members:
        - provider: groq
          model: openai/gpt-oss-120b
        - provider: ovh
          model: gpt-oss-120b
        - provider: sambanova
          model: gpt-oss-120b
        - provider: openrouter
          model: openai/gpt-oss-120b        # платный id; :free → 404 «unavailable for free»
```

Имя модели `auto` (если нет группы с таким именем) значит «любой включённый провайдер, каждому его модель из конфига».

Участнику группы уходит **ровно** его `model`. Запасной список OpenRouter (`models` из `fallback-models`) для такого
вызова не отправляется, `route: fallback` снимается, а в тело OpenRouter добавляется
`provider.allow_fallbacks: false` — иначе OpenRouter отвечает своей запасной моделью (nemotron, poolside/laguna, flint),
иногда ещё и не JSON. Ответ с другим полем `model` (или не-JSON до первого чанка стрима) — сбой этой попытки:
провайдер в cooldown, запрос идёт следующему участнику. Суффикс варианта считается той же моделью
(`openai/gpt-oss-120b:free` и `openai/gpt-oss-120b`). Прямой вызов OpenRouter не из группы по-прежнему шлёт `fallback-models`.

404 с текстом «unavailable» или «No endpoints found» помечает участника недоступным **в этой группе** на
`gateway.model-unavailable-cooldown` (по умолчанию 10 минут). Следующие запросы группы его не вызывают, даже если
провайдерный cooldown уже снят или ping ответил 200. Обычный 404 («Not Found») по-прежнему только короткий cooldown
провайдера. Вне группы 404 «unavailable» клиенту, без этой паузы.

Что слать Nori: **`gpt-oss-120b`** или алиас **`openai/gpt-oss-120b`**. Оба имени есть в `GET /v1/models`.
Неизвестная модель по-прежнему означает «все провайдеры», но обычный 404 тогда не вызывает failover —
для всплеска нужно имя группы.

Провайдер с пустым `api-key` (и без `api-key-optional`) или с битым `base-url` при старте **отключается**
с `WARN` и в группу не входит. Пример: пустой `CLOUDFLARE_ACCOUNT_ID` даёт
`https://api.cloudflare.com/client/v4/accounts//ai/v1` (пустой сегмент пути) — Cloudflare не попадёт в роутинг
и не заберёт на себя весь всплеск с 404.

Также можно ограничить провайдера `max-concurrency` (сколько запросов держать в полёте). У OVH в конфиге
стоит `2` — столько же, сколько анонимных запросов в минуту.

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
├── router/     ProviderRouter (группы, headroom − in-flight, failover), StreamSession, RouterListener
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
