package ru.akiev.gateway.status;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Полный срез состояния шлюза — ответ GET /status и данные события SSE "snapshot".
 * Поля snake_case и стабильны: этот же JSON позже будет читать Telegram-бот.
 */
public record StatusSnapshot(
        @JsonProperty("generated_at") Instant generatedAt,
        List<ProviderStatus> providers,
        Totals today,
        @JsonProperty("hourly") List<HourBucket> hourly,
        List<RecentRequest> recent) {

    /**
     * @param state healthy | cooldown | limit | skipped (skipped — нет api-key)
     */
    public record ProviderStatus(
            String name,
            String model,
            int priority,
            String state,
            @JsonProperty("cooldown_until") Instant cooldownUntil,
            @JsonProperty("cooldown_reason") String cooldownReason,
            @JsonProperty("remaining_requests") Long remainingRequests,
            @JsonProperty("remaining_tokens") Long remainingTokens,
            @JsonProperty("rpm_used") Long rpmUsed,
            @JsonProperty("rpm_limit") Integer rpmLimit,
            @JsonProperty("rpd_used") Long rpdUsed,
            @JsonProperty("rpd_limit") Integer rpdLimit,
            @JsonProperty("requests_today") long requestsToday,
            @JsonProperty("tokens_today") long tokensToday,
            @JsonProperty("errors_today") long errorsToday,
            @JsonProperty("avg_latency_ms") Long avgLatencyMs,
            @JsonProperty("last_error") String lastError,
            @JsonProperty("last_error_at") Instant lastErrorAt,
            @JsonProperty("key_info") KeyInfo keyInfo,
            // Когда провайдер обещал сбросить лимиты (x-ratelimit-reset-*), null — не сообщал.
            @JsonProperty("reset_requests_at") Instant resetRequestsAt,
            @JsonProperty("reset_tokens_at") Instant resetTokensAt,
            // Когда последний раз видели заголовки x-ratelimit-* (null — ни разу: остаток неизвестен).
            @JsonProperty("limits_seen_at") Instant limitsSeenAt,
            // Результат последнего ping (GET /models), null — ещё не пинговали.
            @JsonProperty("last_ping") ProviderHealthService.PingResult lastPing) {
    }

    /** Данные OpenRouter GET /api/v1/key. */
    public record KeyInfo(Map<String, Object> data, @JsonProperty("fetched_at") Instant fetchedAt, String error) {
    }

    public record Totals(long requests, long tokens, long errors) {
    }

    /** Запросы за один час: provider → количество. */
    public record HourBucket(Instant hour, Map<String, Long> requests) {
    }

    public record RecentRequest(
            @JsonProperty("created_at") Instant createdAt,
            String provider,
            String model,
            int status,
            @JsonProperty("latency_ms") long latencyMs,
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens,
            String error) {
    }
}
