package ru.akiev.gateway.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderProperties;

/**
 * Периодически спрашивает OpenRouter о квоте ключа: GET {base-url}/key
 * (для https://openrouter.ai/api/v1 это /api/v1/key). Ответ вида
 * {"data":{"label":..,"limit":..,"usage":..,"limit_remaining":..,"is_free_tier":..}}.
 *
 * <p>Если ключа OpenRouter нет — ничего не делаем. Любая ошибка (сеть, 401) только
 * логируется и сохраняется в {@link #error()}, приложение не падает.
 * Интервал — gateway.openrouter.poll-interval (по умолчанию 5m).
 */
@Component
public class OpenRouterKeyPoller {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterKeyPoller.class);

    private final ProviderProperties openRouter;
    private final WebClient webClient;
    private final Clock clock;

    private volatile Map<String, Object> data;
    private volatile Instant fetchedAt;
    private volatile String error;

    public OpenRouterKeyPoller(GatewayProperties props, WebClient.Builder builder, Clock clock,
            @Value("${gateway.openrouter.name:openrouter}") String providerName) {
        this.clock = clock;
        this.openRouter = props.providers().stream()
                .filter(p -> p.name().equals(providerName) && p.hasApiKey())
                .findFirst().orElse(null);
        this.webClient = openRouter == null ? null : builder.clone()
                .baseUrl(openRouter.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openRouter.apiKey())
                .build();
    }

    public boolean enabled() {
        return openRouter != null;
    }

    public String providerName() {
        return openRouter != null ? openRouter.name() : null;
    }

    // fixedDelayString принимает миллисекунды или ISO-8601 длительность (PT5M = 5 минут).
    @Scheduled(initialDelayString = "${gateway.openrouter.initial-delay:PT5S}",
            fixedDelayString = "${gateway.openrouter.poll-interval:PT5M}")
    public void poll() {
        if (!enabled()) {
            return;
        }
        try {
            Map<String, Object> body = webClient.get().uri("/key")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                    .timeout(Duration.ofSeconds(15))
                    .block();
            Object d = body != null ? body.get("data") : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = d instanceof Map<?, ?> ? (Map<String, Object>) d : body;
            data = m;
            fetchedAt = clock.instant();
            error = null;
            log.info("OpenRouter key: {}", m);
        } catch (WebClientResponseException e) {
            error = "HTTP " + e.getStatusCode().value();
            log.warn("OpenRouter /key вернул {}", e.getStatusCode().value());
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("OpenRouter /key недоступен: {}", e.getMessage());
        }
    }

    public Map<String, Object> data() {
        return data;
    }

    public Instant fetchedAt() {
        return fetchedAt;
    }

    public String error() {
        return error;
    }
}
