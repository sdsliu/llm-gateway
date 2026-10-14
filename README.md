# llm-gateway

OpenAI-совместимый LLM-шлюз на Spring Boot 3 / Java 21.
Этап 2: несколько провайдеров с приоритетами и автоматическим failover, без стриминга.

## Эндпоинты

- `POST /v1/chat/completions` — chat completion (формат OpenAI)
- `GET /v1/models` — список моделей
- `GET /actuator/health` — healthcheck

## Запуск

```bash
export GROQ_API_KEY=gsk_...
./mvnw spring-boot:run
# шлюз слушает http://localhost:4000
```

Тесты: `./mvnw -q test`

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
