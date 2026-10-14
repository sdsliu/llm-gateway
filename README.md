# llm-gateway

OpenAI-совместимый LLM-шлюз на Spring Boot 3 / Java 21.
Этап 4: несколько провайдеров с failover, учёт токенов в PostgreSQL, живой дашборд, стриминг (`stream: true`) и запуск через Docker Compose.

## Эндпоинты

- `POST /v1/chat/completions` — chat completion (формат OpenAI)
- `GET /v1/models` — список моделей
- `GET /actuator/health` — healthcheck

## Запуск

### Вариант 1: всё в Docker Compose (шлюз + PostgreSQL 16)

```bash
cp .env.example .env          # вписать GROQ_API_KEY (и при желании GEMINI_/OPENROUTER_API_KEY)
docker compose up -d --build  # Flyway накатит миграции при старте шлюза
docker compose ps             # оба сервиса должны быть healthy
curl localhost:4000/actuator/health
docker compose down           # остановить (down -v — удалить и данные БД)
```

Если контейнеры не видят друг друга (ограниченная сеть Docker), используйте host-сеть:
`docker compose -f docker-compose.yml -f docker-compose.hostnet.yml up -d --build`.

### Вариант 2: шлюз на хосте, база в Docker

```bash
docker compose up -d postgres
export GROQ_API_KEY=gsk_...
./mvnw spring-boot:run
# шлюз слушает http://localhost:4000
```

Тесты: `./mvnw -q test` (используют H2 в режиме PostgreSQL, Docker не нужен).

Подключение к БД: `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Дашборд и статус

- `http://localhost:4000/` — дашборд: карточки провайдеров (работает / cooldown / лимит / пропущен,
  RPM/RPD, остатки из заголовков), запросы и токены за сегодня, график за 24 часа, последние запросы.
  Обновляется в реальном времени через SSE, без polling.
- `GET /status` — тот же срез в JSON (для curl и будущего Telegram-бота).
- `GET /status/stream` — SSE-поток: `event: snapshot` при подключении, после каждого запроса и при смене
  состояния провайдера; `event: heartbeat` раз в 15 с.
- Каждая попытка обращения к провайдеру (включая ошибки и failover) пишется в `usage_record`.
- Если задан `OPENROUTER_API_KEY`, раз в 5 минут опрашивается `GET /api/v1/key`, остаток кредитов
  виден в `/status` и на карточке OpenRouter.

Настройки — `src/main/resources/application.yml`, список `gateway.providers`
(`name`, `base-url`, `api-key`, `model`, `priority`, `timeout`, `limits.rpm/rpd`).
Модель из запроса клиента подменяется моделью выбранного провайдера.

## Ротация провайдеров

| Провайдер | Переменная | priority |
|---|---|---|
| groq | `GROQ_API_KEY` | 1 |
| gemini | `GEMINI_API_KEY` | 2 |
| openrouter | `OPENROUTER_API_KEY` | 3 |

- Провайдеры с пустым ключом пропускаются при старте.
- Роутер берёт здорового провайдера с наименьшим `priority`.
- 429 / 5xx / 401 / 403 / таймаут → cooldown (`Retry-After`, иначе `gateway.default-cooldown` = 5m) и повтор на следующем.
- Собственные лимиты `rpm`/`rpd` и заголовок `x-ratelimit-remaining-requests = 0` тоже выводят провайдера из ротации.
- Все недоступны → `503` с `{"error":{"type":"service_unavailable","code":"all_providers_unavailable"}}`.

Поддерживаемые заголовки лимитов:

| Заголовок | Кто шлёт | Что делаем |
|---|---|---|
| `x-ratelimit-remaining-requests`, `x-ratelimit-remaining-tokens` | Groq, OpenAI | сохраняем в состоянии; requests = 0 → пауза |
| `x-ratelimit-reset-requests` (`2m59.56s`) | Groq, OpenAI | длительность паузы при requests = 0 |
| `X-RateLimit-Remaining`, `X-RateLimit-Reset` (epoch ms) | OpenRouter | то же |
| `Retry-After` (секунды или HTTP-дата) | все | длительность cooldown после 429/5xx |

## Проверка через curl

```bash
curl -s http://localhost:4000/v1/models

curl -s http://localhost:4000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"openai/coder","messages":[{"role":"user","content":"Скажи привет"}]}'

# стриминг (SSE в формате OpenAI, в конце data: [DONE])
curl -N http://localhost:4000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"openai/coder","stream":true,"messages":[{"role":"user","content":"Перечисли три цвета радуги"}]}'
```

Стриминг: failover на другого провайдера возможен только до первого чанка; если поток оборвался
позже — клиент получит чанк `{"error":...}` и `data: [DONE]`. Usage для учёта запрашивается через
`stream_options.include_usage` (отключается `stream-usage: false` у провайдера); если провайдер usage
не прислал, токены в `usage_record` остаются пустыми.

## Проверка через Aider

```bash
export OPENAI_API_KEY=dummy   # ключ шлюзу не нужен, Aider просто требует переменную
aider --openai-api-base http://localhost:4000/v1 --model openai/coder            # со стримингом
aider --openai-api-base http://localhost:4000/v1 --model openai/coder --no-stream # без стриминга
```

## Изучение WebFlux

См. [docs/webflux-notes.md](docs/webflux-notes.md).
