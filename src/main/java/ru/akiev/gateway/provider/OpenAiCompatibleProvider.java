package ru.akiev.gateway.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Провайдер для любого OpenAI-совместимого API (Groq, Z.ai, OpenRouter, Kilo, OVHcloud, Cloudflare, vLLM, Ollama...).
 *
 * <p>Почему WebClient, а не RestTemplate/RestClient? WebClient — неблокирующий HTTP-клиент
 * из Spring WebFlux. Сейчас мы используем его в «блокирующем» режиме через block(),
 * но код уже написан на реактивном API, и на этапе 4 (стриминг/SSE) переход будет почти
 * механическим: убрать block() и вернуть Mono/Flux наружу. Подробнее — docs/webflux-notes.md.
 *
 * <p>Этап 2: провайдер сам НЕ решает, что делать при ошибке — он только читает заголовки
 * лимитов в {@link ProviderState} и бросает {@link ProviderException} (с Retry-After).
 * Решение о cooldown и переключении принимает ProviderRouter.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private final WebClient webClient;
    private final ProviderProperties props;
    private final Duration timeout;
    private final ProviderState state;
    private final Clock clock;
    private final boolean requestsHeaderPerDay;

    public OpenAiCompatibleProvider(WebClient.Builder builder, ProviderProperties props) {
        this(builder, props, Clock.systemUTC());
    }

    public OpenAiCompatibleProvider(WebClient.Builder builder, ProviderProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.timeout = props.timeout() != null ? props.timeout() : Duration.ofSeconds(120);
        this.state = new ProviderState(props.limits());
        this.requestsHeaderPerDay = ru.akiev.gateway.limits.ProbeKind.of(props).requestsHeaderIsPerDay();
        // WebClient неизменяемый и потокобезопасный: создаём один раз и переиспользуем.
        // baseUrl — общий префикс (например https://api.groq.com/openai/v1),
        // заголовки по умолчанию добавляются к каждому запросу.
        // builder.clone() — чтобы не «испортить» общий Spring-овый билдер нашими настройками
        // (иначе baseUrl одного провайдера «протёк» бы в другого).
        this.webClient = builder.clone()
                .baseUrl(props.baseUrl())
                // Authorization: Bearer — только если ключ задан (keyless-провайдеры ходят без него).
                .defaultHeaders(props::applyAuth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return props.name();
    }

    @Override
    public int priority() {
        return props.priority();
    }

    @Override
    public ProviderState state() {
        return state;
    }

    @Override
    public List<String> models() {
        return props.allModels();
    }

    @Override
    public Integer maxConcurrency() {
        return props.maxConcurrency();
    }

    /**
     * Модель из конфигурации ({@code model} или список {@code models}, включая id из группы) уходит как есть.
     * Любое другое имя клиента (и пустое) заменяется на основную модель провайдера.
     */
    private ChatRequest applyConfiguredModel(ChatRequest request) {
        if (props.model() == null || props.model().isBlank()) {
            return request;
        }
        String requested = request.model();
        if (requested != null && props.allModels().contains(requested)) {
            return request;
        }
        return request.withModel(props.model());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // Override модели: клиент может прислать любое имя (например "openai/coder" от Aider),
        // а провайдеру уходит реальная модель из конфигурации — либо id, который роутер уже
        // подставил из группы моделей.
        ChatRequest outgoing = applyConfiguredModel(request.withoutStream());
        state.recordRequest(clock.instant());

        try {
            ResponseEntity<ChatResponse> entity = webClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON)
                    // bodyValue: объект сериализуется в JSON через Jackson.
                    .bodyValue(ProviderRequestBody.of(outgoing, props.fallbackModels(), props.extraBody()))
                    // retrieve() — «простой» способ получить ответ. Важно: на 4xx/5xx
                    // retrieve() по умолчанию превращает ответ в ошибку
                    // WebClientResponseException (а не возвращает тело как успешное).
                    // Альтернатива — exchangeToMono(), где статус разбираешь вручную.
                    .retrieve()
                    // Этап 1 использовал bodyToMono(ChatResponse.class) — только тело.
                    // Теперь нам нужны ещё и ЗАГОЛОВКИ (x-ratelimit-*), поэтому берём
                    // toEntity(...): Mono<ResponseEntity<ChatResponse>> = статус + заголовки + тело.
                    //
                    // Mono<T> — это ОБЕЩАНИЕ 0 или 1 значения в будущем (как CompletableFuture,
                    // но ленивое). До подписки (subscribe/block) НИЧЕГО не происходит:
                    // HTTP-запрос даже не отправлен — мы только описываем конвейер.
                    .toEntity(ChatResponse.class)
                    // timeout(...) — оператор конвейера: если значение не пришло за указанное
                    // время, Mono завершится ошибкой TimeoutException, а HTTP-запрос будет
                    // отменён. LLM бывают медленными, поэтому по умолчанию 120 секунд.
                    .timeout(timeout)
                    // block() — подписывается на Mono и БЛОКИРУЕТ текущий поток, пока не
                    // придёт результат или ошибка. В MVP это нормально: контроллер у нас
                    // Spring MVC (Tomcat, поток-на-запрос), он и так синхронный, и block()
                    // делает код простым и понятным.
                    // В реактивном стеке (этап 4, WebFlux + SSE) block() вызывать НЕЛЬЗЯ —
                    // он «съедает» event-loop поток Netty (Reactor даже бросит исключение).
                    // Тогда метод будет возвращать Mono<ChatResponse> / Flux<Chunk> наружу,
                    // а подписку сделает сам фреймворк.
                    .block();
            if (entity == null) {
                throw new ProviderException(502, "Provider " + name() + " returned empty response", null);
            }
            updateLimits(entity.getHeaders());
            return entity.getBody();
        } catch (WebClientResponseException e) {
            // Ошибка HTTP от провайдера. Заголовки лимитов приходят и в ответе 429 — читаем их тоже.
            updateLimits(e.getHeaders());
            Duration retryAfter = RateLimitHeaders.retryAfter(e.getHeaders(), clock.instant());
            throw new ProviderException(e.getStatusCode().value(),
                    "Provider " + name() + " returned " + e.getStatusCode().value() + ": "
                            + e.getResponseBodyAsString(), retryAfter, e);
        } catch (ProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            // block() «разворачивает» checked-исключения (TimeoutException) в RuntimeException,
            // поэтому ищем причину по цепочке.
            if (hasCause(e, TimeoutException.class)) {
                throw new ProviderException(504,
                        "Provider " + name() + " timed out after " + timeout, e);
            }
            throw new ProviderException(502, "Provider " + name() + " call failed: " + e.getMessage(), e);
        }
    }

    /** Тип элемента SSE-потока: событие, у которого data — строка (JSON чанка как есть). */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_STRING =
            new ParameterizedTypeReference<>() { };

    /**
     * Этап 4: стриминг. Здесь уже НЕТ block() — метод возвращает Flux наружу.
     *
     * <p>Flux<T> — поток из 0..N элементов во времени (Mono — 0..1). Каждый SSE-чанк провайдера
     * становится отдельным элементом Flux по мере прихода из сети.
     *
     * <p><b>Backpressure (обратное давление)</b>: подписчик сам говорит, сколько элементов он готов
     * принять (request(n)). Reactor Netty не читает из сокета больше, чем запрошено, поэтому медленный
     * клиент шлюза автоматически «притормаживает» чтение от провайдера, а не копит гигабайты в памяти.
     * У нас потребитель — ChatController, который читает поток через toStream(prefetch) и пишет
     * в HTTP-ответ: пока клиент не принял очередной чанк, новые сверх prefetch не запрашиваются.
     */
    @Override
    public Flux<String> chatStream(ChatRequest request) {
        ChatRequest body = applyConfiguredModel(request).withStream(props.streamUsage());
        // Flux.defer — ничего не делаем до подписки (в том числе не считаем запрос в лимитах).
        return Flux.defer(() -> {
            state.recordRequest(clock.instant());
            return webClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(ProviderRequestBody.of(body, props.fallbackModels(), props.extraBody()))
                    .retrieve()
                    // toEntityFlux: Mono<ResponseEntity<Flux<T>>> — сначала приходят статус и
                    // заголовки (читаем x-ratelimit-*), а тело остаётся потоком.
                    .toEntityFlux(SSE_STRING)
                    .flatMapMany(entity -> {
                        updateLimits(entity.getHeaders());
                        return entity.getBody() != null ? entity.getBody() : Flux.<ServerSentEvent<String>>empty();
                    })
                    .map(ServerSentEvent::data)
                    .filter(d -> d != null && !d.isBlank())
                    // [DONE] — маркер конца у OpenAI. Дальше читать нечего; сам маркер наружу
                    // не отдаём — его допишет контроллер (так [DONE] будет ровно один раз).
                    .takeWhile(d -> !"[DONE]".equals(d.trim()))
                    // Для Flux timeout — это максимальная пауза МЕЖДУ элементами (и до первого).
                    // Генерация длинного ответа может идти минуты — это нормально, пока чанки идут.
                    .timeout(timeout);
        }).onErrorMap(e -> !(e instanceof ProviderException), this::toProviderException);
    }

    private ProviderException toProviderException(Throwable e) {
        if (e instanceof WebClientResponseException w) {
            updateLimits(w.getHeaders());
            return new ProviderException(w.getStatusCode().value(),
                    "Provider " + name() + " returned " + w.getStatusCode().value() + ": "
                            + w.getResponseBodyAsString(),
                    RateLimitHeaders.retryAfter(w.getHeaders(), clock.instant()), w);
        }
        if (hasCause(e, TimeoutException.class)) {
            return new ProviderException(504, "Provider " + name() + " stream timed out after " + timeout, e);
        }
        return new ProviderException(502, "Provider " + name() + " stream failed: " + e.getMessage(), e);
    }

    /**
     * Сохраняем остатки лимитов. Если запросов не осталось (remaining = 0) — сразу ставим
     * cooldown до сброса лимита, чтобы роутер не тратил на этого провайдера следующий запрос.
     */
    /**
     * Также (#34, п. 7): сами лимиты из x-ratelimit-limit-* обновляют действующие лимиты (источник headers),
     * а остаток токенов x-ratelimit-remaining-tokens уточняет минутный бюджет TPM.
     */
    public void updateLimits(HttpHeaders headers) {
        ru.akiev.gateway.limits.DiscoveredLimits discovered = RateLimitHeaders.limits(headers, requestsHeaderPerDay);
        if (state.applyHeaderLimits(discovered)) {
            log.info("Провайдер {}: лимиты из заголовков, действующие: {}", name(), state.effectiveLimits());
        }
        Long reqs = RateLimitHeaders.remainingRequests(headers);
        Long toks = RateLimitHeaders.remainingTokens(headers);
        state.updateRemaining(reqs, toks);
        if (reqs != null || toks != null) {
            Instant now = clock.instant();
            Duration rr = RateLimitHeaders.resetRequests(headers, now);
            Duration rt = RateLimitHeaders.resetTokens(headers);
            state.updateResets(now, rr != null ? now.plus(rr) : null, rt != null ? now.plus(rt) : null);
            if (toks != null) {
                state.budget().observeRemaining(now, toks, rt != null ? now.plus(rt) : null);
            }
            log.debug("Провайдер {}: remaining requests={}, tokens={}", name(), reqs, toks);
        }
        if (reqs != null && reqs <= 0) {
            Instant now = clock.instant();
            Duration reset = RateLimitHeaders.resetRequests(headers, now);
            Duration wait = reset != null ? reset : Duration.ofMinutes(1);
            state.cooldown(now.plus(wait), "remaining requests = 0");
            log.warn("Провайдер {}: лимит запросов исчерпан, пауза {}", name(), wait);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
