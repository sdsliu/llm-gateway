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
import org.springframework.util.unit.DataSize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Ping — самый дешёвый запрос: {@code GET {base-url}{ping-path}} (по умолчанию /models). Он не тратит токены и
 * (у Groq/OpenRouter) не считается в лимите chat completions, поэтому мы НЕ учитываем его
 * в LimitTracker. Тот же ответ используем как список моделей провайдера и кэшируем
 * ({@code gateway.health.models-ttl}).
 *
 * <p>Состояние «up» определяется только HTTP-статусом: любой 2xx — доступен, даже если тело не
 * разобралось (модели тогда просто не посчитаны). 404/405 на ping-path тоже «up»: сервер ответил,
 * но список моделей не поддерживает (например, у Z.ai /models не задокументирован).
 * Тело читаем строкой с увеличенным лимитом ({@code gateway.health.max-body-size}): ответ
 * OpenRouter /models больше 1 МБ, а дефолтный лимит WebClient — 256 КБ.
 *
 * <p>Ключ в логи и ответы не попадает: в ошибку пишем только статус и короткое тело.
 */
@Service
public class ProviderHealthService {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
            @Value("${gateway.health.scheduled:false}") boolean scheduled,
            @Value("${gateway.health.max-body-size:16MB}") DataSize maxBodySize) {
        this.clock = clock;
        this.timeout = timeout;
        this.modelsTtl = modelsTtl;
        this.scheduled = scheduled;
        for (ProviderProperties p : props.providers()) {
            providers.put(p.name(), p);
            if (p.enabled()) {
                clients.put(p.name(), builder.clone().baseUrl(p.baseUrl())
                        .codecs(c -> c.defaultCodecs().maxInMemorySize((int) maxBodySize.toBytes()))
                        .defaultHeaders(p::applyAuth).build());
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
        String path = pingPath(providers.get(name));
        String url = pingUrl(providers.get(name));
        return client.get().uri(path).retrieve().toEntity(String.class)
                .timeout(timeout)
                .map(e -> onSuccess(name, e, start))
                .onErrorResume(e -> Mono.just(onError(name, url, e, start)))
                .doOnNext(r -> lastPing.put(name, r));
    }

    private PingResult onSuccess(String name, ResponseEntity<String> e, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        List<String> ids = parseIds(e.getBody());
        if (ids == null) {
            // 2xx, но тело не похоже на список моделей — провайдер всё равно доступен.
            models.compute(name, (k, old) -> new ModelsCache(old != null ? old.models() : List.of(),
                    old != null ? old.fetchedAt() : null, "не удалось разобрать список моделей", clock.instant()));
            return new PingResult(name, "up", ms, e.getStatusCode().value(), null, clock.instant(), null);
        }
        models.put(name, new ModelsCache(ids, clock.instant(), null, clock.instant()));
        return new PingResult(name, "up", ms, e.getStatusCode().value(), null, clock.instant(), ids.size());
    }

    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String b = body.replaceAll("\\s+", " ").trim();
        return b.length() > 200 ? b.substring(0, 200) + "…" : b;
    }

    /** ping-path с ведущим слэшем (по умолчанию /models), чтобы он дописывался к base-url, а не заменял его. */
    static String pingPath(ProviderProperties p) {
        String path = p.pingPath() == null || p.pingPath().isBlank() ? "/models" : p.pingPath().trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Полный URL ping для логов (без ключа: ключ идёт только в заголовке). */
    static String pingUrl(ProviderProperties p) {
        String base = p.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + pingPath(p);
    }

    private PingResult onError(String name, String url, Throwable e, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        Integer status = null;
        String error;
        if (e instanceof WebClientResponseException w && (w.getStatusCode().is2xxSuccessful()
                || w.getStatusCode().value() == 404 || w.getStatusCode().value() == 405)) {
            // 2xx здесь = ошибка чтения/декодирования тела; 404/405 = нет эндпоинта списка моделей.
            // Сервер ответил и ключ не отвергнут — считаем провайдера доступным.
            int st = w.getStatusCode().value();
            if (st >= 300) {
                log.warn("Ping {} GET {} -> HTTP {} (считаем up, список моделей недоступен): {}", name, url, st,
                        snippet(w.getResponseBodyAsString()));
            }
            String note = st < 300 ? "не удалось прочитать список моделей" : "список моделей не поддерживается (HTTP " + st + ")";
            models.compute(name, (k, old) -> new ModelsCache(old != null ? old.models() : List.of(),
                    old != null ? old.fetchedAt() : null, note, clock.instant()));
            return new PingResult(name, "up", ms, st, null, clock.instant(), null);
        }
        if (e instanceof WebClientResponseException w) {
            status = w.getStatusCode().value();
            String body = w.getResponseBodyAsString();
            error = "HTTP " + status + (body.isBlank() ? "" : ": " + snippet(body));
        } else if (e instanceof TimeoutException) {
            error = "таймаут " + timeout.toSeconds() + " с";
        } else {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        log.warn("Ping {} GET {} не прошёл: {}", name, url, error);
        models.compute(name, (k, old) -> new ModelsCache(old != null ? old.models() : List.of(),
                old != null ? old.fetchedAt() : null, error, clock.instant()));
        return new PingResult(name, "down", ms, status, error, clock.instant(), null);
    }

    /**
     * Мягкий разбор списка моделей: {"data":[{"id":...}]} (OpenAI/OpenRouter), голый массив [{"id":...}]
     * или массив строк. Элемент без id пропускаем. null — если тело вообще не JSON или другой формы.
     */
    static List<String> parseIds(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode arr = root.isArray() ? root : root.path("data");
        if (!arr.isArray()) {
            arr = root.path("models");
        }
        if (!arr.isArray()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode n : arr) {
            JsonNode id = n.isTextual() ? n : n.path("id");
            if (id.isValueNode() && !id.asText().isBlank()) {
                ids.add(id.asText());
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
