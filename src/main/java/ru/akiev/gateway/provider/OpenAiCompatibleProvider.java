package ru.akiev.gateway.provider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;

/**
 * Провайдер для любого OpenAI-совместимого API (Groq, OpenRouter, OpenAI, vLLM, Ollama...).
 *
 * <p>Почему WebClient, а не RestTemplate/RestClient? WebClient — неблокирующий HTTP-клиент
 * из Spring WebFlux. Сейчас (этап 1) мы используем его в «блокирующем» режиме через block(),
 * но код уже написан на реактивном API, и на этапе 4 (стриминг/SSE) переход будет почти
 * механическим: убрать block() и вернуть Mono/Flux наружу. Подробнее — docs/webflux-notes.md.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private final WebClient webClient;
    private final ProviderProperties props;
    private final Duration timeout;

    public OpenAiCompatibleProvider(WebClient.Builder builder, ProviderProperties props) {
        this.props = props;
        this.timeout = props.timeout() != null ? props.timeout() : Duration.ofSeconds(120);
        // WebClient неизменяемый и потокобезопасный: создаём один раз и переиспользуем.
        // baseUrl — общий префикс (например https://api.groq.com/openai/v1),
        // заголовки по умолчанию добавляются к каждому запросу.
        // builder.clone() — чтобы не «испортить» общий Spring-овый билдер нашими настройками.
        this.webClient = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return props.name();
    }

    @Override
    public List<String> models() {
        return props.model() != null ? List.of(props.model()) : List.of();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // Override модели: клиент может прислать любое имя (например "openai/coder" от Aider),
        // а провайдеру уходит реальная модель из конфигурации.
        ChatRequest outgoing = request.withoutStream();
        if (props.model() != null && !props.model().isBlank()) {
            outgoing = outgoing.withModel(props.model());
        }

        try {
            return webClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON)
                    // bodyValue: объект сериализуется в JSON через Jackson.
                    .bodyValue(outgoing)
                    // retrieve() — «простой» способ получить тело ответа. Важно: на 4xx/5xx
                    // retrieve() по умолчанию превращает ответ в ошибку
                    // WebClientResponseException (а не возвращает тело как успешное).
                    // Альтернатива — exchangeToMono(), где статус разбираешь вручную.
                    .retrieve()
                    // bodyToMono(ChatResponse.class) — «когда тело придёт, распарси его
                    // в ChatResponse». Возвращает Mono<ChatResponse>.
                    //
                    // Mono<T> — это ОБЕЩАНИЕ 0 или 1 значения в будущем (как CompletableFuture,
                    // но ленивое). До подписки (subscribe/block) НИЧЕГО не происходит:
                    // HTTP-запрос даже не отправлен — мы только описываем конвейер.
                    .bodyToMono(ChatResponse.class)
                    // timeout(...) — оператор конвейера: если значение не пришло за указанное
                    // время, Mono завершится ошибкой TimeoutException, а HTTP-запрос будет
                    // отменён. LLM бывают медленными, поэтому 120 секунд.
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
        } catch (WebClientResponseException e) {
            // Ошибка HTTP от провайдера: пробрасываем статус и тело клиенту.
            throw new ProviderException(e.getStatusCode().value(),
                    "Provider " + name() + " returned " + e.getStatusCode().value() + ": "
                            + e.getResponseBodyAsString(), e);
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

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
