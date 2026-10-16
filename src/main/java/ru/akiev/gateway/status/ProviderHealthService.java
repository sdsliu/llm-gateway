package ru.akiev.gateway.status;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderProperties;

/**
 * Проверка здоровья провайдеров и список их моделей.
 *
 * <p>Ping — самый дешёвый запрос: {@code GET {base-url}/models}. Он не тратит токены и
 * (у Groq/OpenRouter/Gemini) не считается в лимите chat completions, поэтому мы НЕ учитываем его
 * в LimitTracker. Тот же ответ используем как список моделей провайдера и кэшируем
 * ({@code gateway.health.models-ttl}).
 *
 * <p>Ключ в логи и ответы не попадает: в ошибку пишем только статус и короткое тело.
 */
@Service
public class ProviderHealthService {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final Map<String, ProviderProperties> providers = new LinkedHashMap<>();
    private final Map<String, WebClient> clients = new LinkedHashMap<>();
    private final Map<String, PingResult> lastPing = new ConcurrentHashMap<>();
    private final Map<String, ModelsCache> models = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Duration modelsTtl;
    private final boolean scheduled;
    private final Clock clock;

    public ProviderHealthService(GatewayProperties props, WebClient.Builder builder, Clock clock,
            @Value("${gateway.health.timeout:PT10S}") Duration timeout,
            @Value("${gateway.health.models-ttl:PT10M}") Duration modelsTtl,
            @Value("${gateway.health.scheduled:false}") boolean scheduled) {
        this.clock = clock;
        this.timeout = timeout;
        this.modelsTtl = modelsTtl;
        this.scheduled = scheduled;
        for (ProviderProperties p : props.providers()) {
            providers.put(p.name(), p);
            if (p.hasApiKey()) {
                clients.put(p.name(), builder.clone().baseUrl(p.baseUrl())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + p.apiKey()).build());
            }
        }
    }

    public boolean exists(String name) {
        return providers.containsKey(name);
    }

    public PingResult ping(String name) {
        return pingMono(name).block();
    }

    public List<PingResult> pingAll() {
        return Flux.fromIterable(providers.keySet()).flatMapSequential(this::pingMono).collectList().block();
    }

    /** Опциональный пинг по расписанию: gateway.health.scheduled=true, интервал gateway.health.interval. */
    @Scheduled(initialDelayString = "${gateway.health.initial-delay:PT10S}",
            fixedDelayString = "${gateway.health.interval:PT5M}")
    public void scheduledPing() {
        if (scheduled) {
            pingAll();
        }
    }

    public Optional<PingResult> lastPing(String name) {
        return Optional.ofNullable(lastPing.get(name));
    }

    /** Модели провайдера из кэша; при refresh или устаревшем кэше — перезапрашиваем /models. */
    public ModelsCache models(String name, boolean refresh) {
        ModelsCache c = models.get(name);
        // TTL считаем от последней попытки (удачной или нет), чтобы не долбить упавшего провайдера.
        boolean stale = c == null || c.checkedAt().plus(modelsTtl).isBefore(clock.instant());
        if ((refresh || stale) && clients.containsKey(name)) {
            pingMono(name).block();
            c = models.get(name);
        }
        if (c == null) {
            return new ModelsCache(List.of(), null, clients.containsKey(name) ? "ещё не загружено" : "api-key не задан", null);
        }
        return c;
    }

    public Map<String, ModelsCache> allModels(boolean refresh) {
        Map<String, ModelsCache> m = new LinkedHashMap<>();
        providers.keySet().forEach(n -> m.put(n, models(n, refresh)));
        return m;
    }

    private Mono<PingResult> pingMono(String name) {
        WebClient client = clients.get(name);
        if (client == null) {
            PingResult r = new PingResult(name, "skipped", null, null, "api-key не задан", clock.instant(), null);
            lastPing.put(name, r);
            return Mono.just(r);
        }
        long start = System.nanoTime();
        return client.get().uri("/models").retrieve().toEntity(MAP)
                .timeout(timeout)
                .map(e -> onSuccess(name, e, start))
                .onErrorResume(e -> Mono.just(onError(name, e, start)))
                .doOnNext(r -> lastPing.put(name, r));
    }

    private PingResult onSuccess(String name, ResponseEntity<Map<String, Object>> e, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        List<String> ids = parseIds(e.getBody());
        models.put(name, new ModelsCache(ids, clock.instant(), null, clock.instant()));
        return new PingResult(name, "up", ms, e.getStatusCode().value(), null, clock.instant(), ids.size());
    }

    private PingResult onError(String name, Throwable e, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        Integer status = null;
        String error;
        if (e instanceof WebClientResponseException w) {
            status = w.getStatusCode().value();
            String body = w.getResponseBodyAsString();
            error = "HTTP " + status + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(200, body.length())));
        } else if (e instanceof TimeoutException) {
            error = "таймаут " + timeout.toSeconds() + " с";
        } else {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        log.warn("Ping {} не прошёл: {}", name, status != null ? "HTTP " + status : e.getClass().getSimpleName());
        models.compute(name, (k, old) -> new ModelsCache(old != null ? old.models() : List.of(),
                old != null ? old.fetchedAt() : null, error, clock.instant()));
        return new PingResult(name, "down", ms, status, error, clock.instant(), null);
    }

    /** OpenAI-формат: {"data":[{"id":"..."}]}. Gemini OpenAI-compat отдаёт id вида "models/gemini-2.5-flash". */
    static List<String> parseIds(Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        if (body != null && body.get("data") instanceof List<?> data) {
            for (Object o : data) {
                if (o instanceof Map<?, ?> m && m.get("id") != null) {
                    ids.add(String.valueOf(m.get("id")));
                }
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /** @param state up | down | skipped */
    public record PingResult(String provider, String state,
            @JsonProperty("latency_ms") Long latencyMs,
            @JsonProperty("http_status") Integer httpStatus,
            String error,
            @JsonProperty("checked_at") Instant checkedAt,
            @JsonProperty("models_count") Integer modelsCount) {
    }

    /** fetched_at — последний успешный ответ /models, checked_at — последняя попытка. */
    public record ModelsCache(List<String> models, @JsonProperty("fetched_at") Instant fetchedAt, String error,
            @JsonProperty("checked_at") Instant checkedAt) {
    }
}
