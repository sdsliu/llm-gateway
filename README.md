# llm-gateway

OpenAI-совместимый LLM-шлюз на Spring Boot 3 / Java 21.
Этап 1: прокси MVP — один провайдер (по умолчанию Groq), без стриминга.

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

Настройки — `src/main/resources/application.yml`, блок `gateway.provider`
(`name`, `base-url`, `api-key`, `model`, `timeout`). Модель из запроса клиента
подменяется моделью из конфига.

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
