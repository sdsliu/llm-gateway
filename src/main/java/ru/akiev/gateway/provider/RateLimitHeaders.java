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
 *   <li>Все: {@code Retry-After} — секунды или HTTP-дата (RFC 1123)</li>
 * </ul>
 * HttpHeaders в Spring регистронезависимы, поэтому "X-RateLimit-Remaining" == "x-ratelimit-remaining".
 */
public final class RateLimitHeaders {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");

    private RateLimitHeaders() {
    }

    public static Long remainingRequests(HttpHeaders h) {
        Long v = parseLong(h.getFirst("x-ratelimit-remaining-requests"));
        return v != null ? v : parseLong(h.getFirst("x-ratelimit-remaining"));
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
        return null;
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
