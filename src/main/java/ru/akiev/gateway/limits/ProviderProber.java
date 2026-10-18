package ru.akiev.gateway.limits;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import ru.akiev.gateway.config.ProviderProperties;

/**
 * Опрос одного провайдера: «жив ли он» и «какие у него лимиты» (issue #34, п. 1).
 *
 * <p><b>Реактивная часть — коротко.</b> Каждый метод возвращает {@link Mono} — «обещание» одного результата.
 * Пока на Mono никто не подписался, НИЧЕГО не происходит: запрос в сеть не уходит. Мы лишь описываем цепочку:
 * <ul>
 *   <li>{@code client.get().uri(..).retrieve().toBodilessEntity()} — HTTP-запрос; результат — ResponseEntity
 *       без тела (статус + заголовки). Статус 4xx/5xx превращается в ошибку {@link WebClientResponseException};</li>
 *   <li>{@code .flatMap(x -> другойMono)} — «когда придёт x, запусти следующий запрос» (последовательная цепочка
 *       без блокировки потока: поток Netty освобождается, пока ждём ответ);</li>
 *   <li>{@code .map(x -> y)} — синхронно превратить результат в другой;</li>
 *   <li>{@code .timeout(d)} — если за d ничего не пришло, вместо результата — ошибка TimeoutException
 *       (и HTTP-соединение отменяется). Именно это гарантирует, что старт шлюза не зависнет;</li>
 *   <li>{@code .onErrorResume(e -> Mono.just(..))} — превратить ошибку в обычный результат «не ответил».</li>
 * </ul>
 * Подписывается (и ждёт) {@code LimitDiscoveryService}: при старте — {@code block()} на всех провайдерах сразу.
 */
public class ProviderProber {

    /** Groq: x-ratelimit-* приходят только в ответе на chat, поэтому шлём минимальный запрос. */
    static final String TINY_PROMPT = "ping";

    private static final Pattern INTERVAL = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?");

    private final WebClient.Builder builder;
    private final Clock clock;

    public ProviderProber(WebClient.Builder builder, Clock clock) {
        this.builder = builder;
        this.clock = clock;
    }

    /**
     * Опросить провайдера. Mono никогда не завершается ошибкой: любые сбои (таймаут, 5xx, 401, 429, сеть)
     * становятся {@code ProbeResult.ok=false}.
     */
    public Mono<ProbeResult> probe(ProviderProperties props, Duration timeout) {
        ProbeKind kind = ProbeKind.of(props);
        if (kind == ProbeKind.NONE) {
            return Mono.just(ProbeResult.ok("не опрашивается (limits-probe: none)", null, null, null, null)
                    .timed(0, clock.instant()));
        }
        // Отдельный WebClient на опрос: baseUrl и ключ провайдера. clone() — чтобы не испортить общий билдер.
        WebClient client = builder.clone().baseUrl(props.baseUrl()).defaultHeaders(props::applyAuth).build();
        String method = describe(kind, props);
        Mono<ProbeResult> call = switch (kind) {
            case GROQ -> groq(client, props, method);
            case OPENROUTER -> openRouter(client, props, method);
            case GONKA -> gonka(client, props, method);
            default -> models(client, props, method);
        };
        // Mono.defer — время старта меряем в момент ПОДПИСКИ (реального запуска), а не сборки цепочки.
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return call
                    .timeout(timeout)
                    .onErrorResume(e -> Mono.just(failure(method, e, timeout)))
                    .map(r -> r.timed((System.nanoTime() - start) / 1_000_000, clock.instant()));
        });
    }

    static String describe(ProbeKind kind, ProviderProperties p) {
        return switch (kind) {
            case GROQ -> "GET /models + POST /chat/completions (max_tokens=1)";
            case OPENROUTER -> "GET " + (p.limitsProbeUrl() != null ? p.limitsProbeUrl() : "/key");
            case GONKA -> "GET " + (p.limitsProbeUrl() != null ? p.limitsProbeUrl() : "/test-auth");
            case NONE -> "—";
            default -> "GET " + pingPath(p);
        };
    }

    // ---------- Groq: заголовки x-ratelimit-* ----------

    /**
     * Сначала GET /models (дёшево, проверяет ключ), затем — только если он успешен (flatMap) — крошечный
     * chat с max_tokens=1: лимиты Groq (limit-requests = RPD, limit-tokens = TPM) есть только в ответе chat.
     * Ответ chat 4xx (кроме 429) не делает провайдера «мёртвым»: GET /models уже прошёл, просто лимиты
     * останутся из конфига (но заголовки ошибки тоже читаем — Groq присылает их и в 4xx).
     */
    private Mono<ProbeResult> groq(WebClient client, ProviderProperties props, String method) {
        Map<String, Object> body = new LinkedHashMap<>(props.extraBody());
        body.put("model", props.model());
        body.put("messages", List.of(Map.of("role", "user", "content", TINY_PROMPT)));
        body.put("max_tokens", 1);
        body.put("stream", false);
        return client.get().uri(pingPath(props)).retrieve().toBodilessEntity()
                .flatMap(models -> client.post().uri("/chat/completions").bodyValue(body)
                        .retrieve().toBodilessEntity()
                        .map(chat -> ProbeResult.ok(method, null, chat.getHeaders(), chat.getStatusCode().value(), null))
                        .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode().value() == 429
                                ? Mono.error(e)
                                : Mono.just(ProbeResult.ok(method, null, e.getHeaders(), e.getStatusCode().value(),
                                        "chat вернул " + e.getStatusCode().value() + ", лимиты из конфига"))));
    }

    // ---------- OpenRouter: GET /key ----------

    /**
     * GET /key → {"data": {"limit": …, "limit_remaining": …, "is_free_tier": true, "rate_limit": {"requests": 20,
     * "interval": "60s"}}}. rate_limit (если положительный) → rpm. Для бесплатных моделей (:free) действует
     * правило OpenRouter: 50 запросов в сутки на free tier и 1000 — если куплено ≥ 10 кредитов → rpd.
     */
    private Mono<ProbeResult> openRouter(WebClient client, ProviderProperties props, String method) {
        String url = props.limitsProbeUrl() != null ? props.limitsProbeUrl() : "/key";
        return client.get().uri(url).retrieve().toEntity(JsonNode.class)
                .map(e -> ProbeResult.ok(method, parseOpenRouterKey(e.getBody(), props.model()), null,
                        e.getStatusCode().value(), null));
    }

    static DiscoveredLimits parseOpenRouterKey(JsonNode root, String model) {
        JsonNode d = root == null ? null : root.path("data");
        if (d == null || d.isMissingNode()) {
            return DiscoveredLimits.NONE;
        }
        Long rpm = null;
        JsonNode rl = d.path("rate_limit");
        long requests = rl.path("requests").asLong(-1);
        Duration interval = parseInterval(rl.path("interval").asText(null));
        if (requests > 0 && interval != null && !interval.isZero()) {
            rpm = Math.max(1, requests * 60_000 / interval.toMillis());
        }
        Long rpd = null;
        boolean freeModel = model != null && model.endsWith(":free");
        if (freeModel && d.has("is_free_tier") && !d.get("is_free_tier").isNull()) {
            rpd = d.get("is_free_tier").asBoolean() ? 50L : 1000L;
        }
        return new DiscoveredLimits(rpm, rpd, null, null, null);
    }

    static Duration parseInterval(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        Matcher m = INTERVAL.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        long ms = (long) switch (unit) {
            case "ms" -> n;
            case "m" -> n * 60_000;
            case "h" -> n * 3_600_000;
            default -> n * 1000;
        };
        return Duration.ofMillis(ms);
    }

    // ---------- Gonka Broker: GET /test-auth ----------

    /** GET /test-auth → где-то в JSON maxRpm (ищем рекурсивно: max_rpm / maxRpm / rpm), а также TPM и месяц, если есть. */
    private Mono<ProbeResult> gonka(WebClient client, ProviderProperties props, String method) {
        String url = props.limitsProbeUrl() != null ? props.limitsProbeUrl() : "/test-auth";
        return client.get().uri(url).retrieve().toEntity(JsonNode.class)
                .map(e -> ProbeResult.ok(method, parseGonka(e.getBody()), e.getHeaders(), e.getStatusCode().value(),
                        null));
    }

    static DiscoveredLimits parseGonka(JsonNode root) {
        return new DiscoveredLimits(
                findLong(root, "maxrpm", "rpm", "requestsperminute"),
                findLong(root, "maxrpd", "rpd", "requestsperday"),
                findLong(root, "maxtpm", "tpm", "tokensperminute"),
                findLong(root, "tokensperday", "maxtokensperday"),
                findLong(root, "tokenspermonth", "maxtokenspermonth", "monthlytokens", "monthlytokenlimit"));
    }

    /** Первое положительное число под одним из ключей (без учёта регистра и «_»), обход в глубину. */
    static Long findLong(JsonNode n, String... keys) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
                for (String key : keys) {
                    if (k.equals(key) && e.getValue().isNumber() && e.getValue().asLong() > 0) {
                        return e.getValue().asLong();
                    }
                }
            }
        }
        if (n.isContainerNode()) {
            for (JsonNode child : n) {
                Long v = findLong(child, keys);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    // ---------- Прочие: обычный ping ----------

    /** GET {ping-path}: 2xx — жив; 404/405 — сервер ответил, просто нет списка моделей → тоже жив. */
    private Mono<ProbeResult> models(WebClient client, ProviderProperties props, String method) {
        return client.get().uri(pingPath(props)).retrieve().toBodilessEntity()
                .map((ResponseEntity<Void> e) -> ProbeResult.ok(method, null, e.getHeaders(), e.getStatusCode().value(),
                        null))
                .onErrorResume(WebClientResponseException.class, e -> {
                    int s = e.getStatusCode().value();
                    return s == 404 || s == 405
                            ? Mono.just(ProbeResult.ok(method, null, e.getHeaders(), s, "ping " + s + ", лимиты из конфига"))
                            : Mono.error(e);
                });
    }

    static String pingPath(ProviderProperties p) {
        String path = p.pingPath() == null || p.pingPath().isBlank() ? "/models" : p.pingPath().trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Ошибка → «не ответил»: таймаут, HTTP-статус (401 — ключ, 429 — лимит, 5xx) или сеть. Ключи в текст не попадают. */
    private static ProbeResult failure(String method, Throwable e, Duration timeout) {
        if (e instanceof WebClientResponseException w) {
            int s = w.getStatusCode().value();
            String why = s == 429 ? "429 Too Many Requests" : s == 401 || s == 403 ? s + ": ключ отклонён" : "HTTP " + s;
            return ProbeResult.failed(method, s, why);
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return ProbeResult.failed(method, null, "нет ответа за " + timeout.toMillis() + " мс");
            }
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ProbeResult.failed(method, null, msg.length() > 200 ? msg.substring(0, 200) : msg);
    }

    /** Есть ли в заголовках хоть что-то про лимиты (для лога «лимиты из заголовков не пришли»). */
    static boolean hasRateLimitHeaders(HttpHeaders h) {
        return h != null && h.keySet().stream().anyMatch(k -> k.toLowerCase(java.util.Locale.ROOT).contains("ratelimit"));
    }
}
