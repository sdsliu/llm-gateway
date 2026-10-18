package ru.akiev.gateway.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * Разбор заголовков лимитов. Поддерживаемые варианты:
 * <ul>
 *   <li>Groq / OpenAI: {@code x-ratelimit-remaining-requests}, {@code x-ratelimit-remaining-tokens},
 *       {@code x-ratelimit-reset-requests} (формат "2m59.56s", "7.66s", "120ms")</li>
 *   <li>OpenRouter: {@code X-RateLimit-Remaining} (трактуем как запросы),
 *       {@code X-RateLimit-Reset} (epoch в миллисекундах)</li>
 *   <li>OVHcloud AI Endpoints (и другие по IETF draft RateLimit headers): {@code ratelimit-remaining},
 *       {@code ratelimit-reset} (секунды до сброса), а также {@code x-ratelimit-remaining-minute}</li>
 *   <li>Все: {@code Retry-After} — секунды или HTTP-дата (RFC 1123)</li>
 * </ul>
 * Сами лимиты (не остатки) — {@link #limits}: {@code x-ratelimit-limit-requests/-tokens} (Groq, OpenAI),
 * {@code -requests-day}/{@code -tokens-day} (SambaNova), {@code x-ratelimit-limit-minute/-day} (Kong, OVH),
 * {@code ratelimit-limit} + {@code ratelimit-policy: 2;w=60} (IETF draft).
 * HttpHeaders в Spring регистронезависимы, поэтому "X-RateLimit-Remaining" == "x-ratelimit-remaining".
 */
public final class RateLimitHeaders {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");

    private RateLimitHeaders() {
    }

    public static Long remainingRequests(HttpHeaders h) {
        for (String name : new String[] {"x-ratelimit-remaining-requests", "x-ratelimit-remaining",
                "x-ratelimit-remaining-minute", "ratelimit-remaining"}) {
            Long v = parseLong(h.getFirst(name));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Лимиты, которые провайдер сообщил в заголовках. Поля, которых нет, — null.
     *
     * @param requestsPerDay true для Groq: у него x-ratelimit-limit-requests — это RPD, а не RPM
     */
    public static ru.akiev.gateway.limits.DiscoveredLimits limits(HttpHeaders h, boolean requestsPerDay) {
        Long rpm = null;
        Long rpd = null;
        Long requests = parseLong(h.getFirst("x-ratelimit-limit-requests"));
        if (requestsPerDay) {
            rpd = requests;
        } else {
            rpm = requests;
        }
        rpm = first(rpm, parseLong(h.getFirst("x-ratelimit-limit-requests-minute")),
                parseLong(h.getFirst("x-ratelimit-limit-minute")));
        rpd = first(rpd, parseLong(h.getFirst("x-ratelimit-limit-requests-day")),
                parseLong(h.getFirst("x-ratelimit-limit-day")));
        // IETF draft: "ratelimit-limit: 2" + "ratelimit-policy: 2;w=60" — окно в секундах.
        Long ietf = parseLong(h.getFirst("ratelimit-limit"));
        String policy = h.getFirst("ratelimit-policy");
        if (ietf != null && policy != null) {
            Matcher w = Pattern.compile("w=(\\d+)").matcher(policy);
            if (w.find()) {
                long window = Long.parseLong(w.group(1));
                if (window == 60) {
                    rpm = first(rpm, ietf);
                } else if (window == 86_400) {
                    rpd = first(rpd, ietf);
                }
            }
        }
        Long tpm = parseLong(h.getFirst("x-ratelimit-limit-tokens"));
        Long tpd = parseLong(h.getFirst("x-ratelimit-limit-tokens-day"));
        return new ru.akiev.gateway.limits.DiscoveredLimits(positive(rpm), positive(rpd), positive(tpm),
                positive(tpd), null);
    }

    private static Long first(Long... values) {
        for (Long v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Long positive(Long v) {
        return v == null || v <= 0 ? null : v;
    }

    public static Long remainingTokens(HttpHeaders h) {
        return parseLong(h.getFirst("x-ratelimit-remaining-tokens"));
    }

    /** Через сколько восстановится лимит запросов (если провайдер это сообщил). */
    public static Duration resetRequests(HttpHeaders h, Instant now) {
        Duration d = parseGoDuration(h.getFirst("x-ratelimit-reset-requests"));
        if (d != null) {
            return d;
        }
        Long epochMs = parseLong(h.getFirst("x-ratelimit-reset"));
        if (epochMs != null && epochMs > 1_000_000_000_000L) {
            Duration left = Duration.between(now, Instant.ofEpochMilli(epochMs));
            return left.isNegative() ? Duration.ZERO : left;
        }
        // IETF draft: ratelimit-reset — секунды до сброса окна (OVHcloud: "48")
        Long seconds = parseLong(h.getFirst("ratelimit-reset"));
        if (seconds != null && seconds >= 0 && seconds < 1_000_000_000L) {
            return Duration.ofSeconds(seconds);
        }
        return null;
    }

    /** Через сколько восстановится лимит токенов (x-ratelimit-reset-tokens, Groq/OpenAI). */
    public static Duration resetTokens(HttpHeaders h) {
        return parseGoDuration(h.getFirst("x-ratelimit-reset-tokens"));
    }

    /** Retry-After: "120" (секунды) или "Wed, 21 Oct 2015 07:28:00 GMT". */
    public static Duration retryAfter(HttpHeaders h, Instant now) {
        String v = h.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null || v.isBlank()) {
            return null;
        }
        v = v.trim();
        try {
            return Duration.ofMillis((long) (Double.parseDouble(v) * 1000));
        } catch (NumberFormatException ignored) {
            // не число — пробуем дату
        }
        try {
            Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration left = Duration.between(now, at);
            return left.isNegative() ? Duration.ZERO : left;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Формат длительности в стиле Go, который использует Groq: "1h2m3.5s", "250ms". */
    static Duration parseGoDuration(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        Matcher m = DURATION_PART.matcher(v.trim());
        double millis = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            double n = Double.parseDouble(m.group(1));
            millis += switch (m.group(2)) {
                case "h" -> n * 3_600_000;
                case "m" -> n * 60_000;
                case "s" -> n * 1000;
                default -> n; // ms
            };
        }
        return found ? Duration.ofMillis((long) millis) : null;
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
