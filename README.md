# llm-gateway

OpenAI-совместимый LLM-шлюз на Spring Boot 3 / Java 21.
Этап 3: несколько провайдеров с failover, учёт токенов в PostgreSQL и живой дашборд. Без стриминга.

## Эндпоинты

- `POST /v1/chat/completions` — chat completion (формат OpenAI)
- `GET /v1/models` — список моделей
- `GET /actuator/health` — healthcheck

## Запуск

```bash
docker compose up -d          # PostgreSQL 16 на localhost:5432 (gateway/gateway)
# или: docker run -d --name llm-gw-pg -e POSTGRES_DB=gateway -e POSTGRES_USER=gateway \
#        -e POSTGRES_PASSWORD=gateway -p 5432:5432 postgres:16-alpine
export GROQ_API_KEY=gsk_...
./mvnw spring-boot:run        # Flyway сам создаст таблицу usage_record
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
```

## Проверка через Aider

```bash
export OPENAI_API_KEY=dummy   # ключ шлюзу не нужен, Aider просто требует переменную
aider --openai-api-base http://localhost:4000/v1 --model openai/coder --no-stream
```

## Изучение WebFlux

См. [docs/webflux-notes.md](docs/webflux-notes.md).
