# Заметки по WebFlux / WebClient

Короткий гид: как WebClient работает в шлюзе сейчас (этап 1) и что изменится на этапе 4 (реактивность и SSE-стриминг).

## 1. Что у нас сейчас

- Сервер — **Spring MVC** (Tomcat, один поток на запрос). `ChatController` синхронный: возвращает `ChatResponse`.
- Исходящий HTTP-клиент — **WebClient** из `spring-boot-starter-webflux` (под капотом Reactor Netty).
- Spring Boot, увидев оба стартера (`web` и `webflux`), запускает именно MVC-сервер. WebFlux нам пока нужен только ради WebClient.

Путь запроса:

```
клиент (curl/Aider) → ChatController.chat() → OpenAiCompatibleProvider.chat()
   → webClient.post()...bodyToMono(ChatResponse.class).timeout(120s).block()
   → Groq / любой OpenAI-совместимый API
```

## 2. Основные понятия

| Понятие | Что это |
|---|---|
| `Mono<T>` | «Обещание» 0 или 1 значения в будущем. Ленивое: пока никто не подписался, ничего не выполняется (даже HTTP-запрос не уходит). |
| `Flux<T>` | Поток 0..N значений. Понадобится для стриминга: каждый SSE-чанк — элемент Flux. |
| `retrieve()` | Простой способ получить ответ. На 4xx/5xx сам превращает ответ в `WebClientResponseException`. |
| `exchangeToMono()` | Ручной режим: сам смотришь статус/заголовки и решаешь, что делать. |
| `bodyToMono(X.class)` | «Распарси тело в X, когда оно придёт» → `Mono<X>`. |
| `bodyToFlux(X.class)` | То же, но для потока элементов (SSE, NDJSON). |
| `timeout(Duration)` | Оператор: если значение не пришло вовремя — ошибка `TimeoutException`, запрос отменяется. |
| `block()` | Подписаться и **заблокировать поток** до результата. Мост из реактивного мира в обычный синхронный код. |
| `subscribe()` | Подписаться без блокировки (результат приходит в колбэк). В WebFlux подписку делает сам фреймворк. |

Важная идея: цепочка `post().uri().bodyValue().retrieve().bodyToMono().timeout()` — это только **описание** конвейера. Работа начинается в момент подписки (`block()`).

## 3. Почему `block()` в MVP — нормально

- Контроллер MVC и так занимает поток Tomcat на всё время запроса; `block()` просто ждёт в этом же потоке.
- Код простой, легко отлаживать и тестировать.
- Ошибки ловятся обычным `try/catch` (`WebClientResponseException`, `TimeoutException` в цепочке причин).

Минус: поток занят всё время ожидания LLM (до 120 с). При сотнях параллельных запросов нужны сотни потоков. (Альтернатива на Java 21 — виртуальные потоки: `spring.threads.virtual.enabled=true`.)

## 4. Что изменится на этапе 4 (реактивность + SSE)

1. Убираем `spring-boot-starter-web` → сервер становится WebFlux (Netty, event loop, мало потоков).
2. **`block()` вызывать нельзя**: он заблокирует event-loop поток Netty (Reactor бросит `IllegalStateException: block() is not supported in thread reactor-http-nio-*`).
3. Интерфейс провайдера станет реактивным:
   ```java
   Mono<ChatResponse> chat(ChatRequest request);          // обычный ответ
   Flux<ChatChunk> chatStream(ChatRequest request);       // stream=true
   ```
4. Контроллер возвращает `Mono`/`Flux`, подписывается Spring:
   ```java
   @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
   public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest req) { ... }
   ```
5. Стриминг от провайдера читаем так:
   ```java
   webClient.post().uri("/chat/completions").bodyValue(req.withStream(true))
       .retrieve()
       .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
       .takeUntil(e -> "[DONE]".equals(e.data()));
   ```
   и отдаём клиенту в формате OpenAI: `data: {...chunk...}\n\n`, в конце `data: [DONE]`.
6. Ошибки обрабатываются операторами, а не `try/catch`: `onErrorMap`, `onErrorResume`, `retryWhen`.
7. Тесты: `WebTestClient` вместо `MockMvc`, `StepVerifier` для проверки `Mono`/`Flux`.

## 5. Что почитать

- Spring docs: «WebClient» и «Spring WebFlux».
- Project Reactor reference: разделы про `Mono`, `Flux`, операторы `map`/`flatMap`/`timeout`/`onErrorResume`.

## 6. Этап 2: что поменялось в WebClient

- Вместо `bodyToMono(ChatResponse.class)` используем `toEntity(ChatResponse.class)` → `Mono<ResponseEntity<ChatResponse>>`. Так видны статус и **заголовки** (`x-ratelimit-remaining-*`), а не только тело.
- На 4xx/5xx `retrieve()` бросает `WebClientResponseException`; у неё тоже есть `getHeaders()` — оттуда читаем `Retry-After`.
- Failover сейчас — обычный цикл `for` с `try/catch` в `ProviderRouter`. В реактивной версии (этап 4) это будет цепочка вида
  ```java
  Flux.fromIterable(providers)
      .filter(p -> p.state().isAvailable(now))
      .concatMap(p -> p.chat(req).onErrorResume(ProviderException.class, e -> { cooldown(p, e); return Mono.empty(); }))
      .next()
      .switchIfEmpty(Mono.error(new AllProvidersUnavailableException(...)));
  ```
  `concatMap` пробует провайдеров строго по очереди, `next()` берёт первый успешный ответ и отменяет остальное.
