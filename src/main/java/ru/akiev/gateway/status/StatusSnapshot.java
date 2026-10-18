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
        List<RecentRequest> recent,
        // Текущий режим роутинга (PRIORITY / ROUND_ROBIN, primary, порядок перебора).
        RoutingInfo routing) {

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
            @JsonProperty("last_ping") ProviderHealthService.PingResult lastPing,
            // true — провайдер работает без ключа (api-key-optional и api-key не задан): анонимные лимиты.
            boolean keyless,
            // Лимиты (issue #34): по каждому — значение, сколько использовано, остаток и источник (api/headers/config).
            LimitsView limits,
            // Исключён из роутинга: не ответил на опрос при старте / перепинге (или ответил 429).
            boolean excluded,
            @JsonProperty("excluded_reason") String excludedReason,
            @JsonProperty("excluded_since") Instant excludedSince,
            // Последний опрос лимитов (LimitDiscoveryService), null — не опрашивали (startup-probe=false).
            @JsonProperty("limits_probe") ProbeView limitsProbe) {
    }

    /**
     * Один лимит.
     *
     * @param limit     значение лимита, null — не ограничен (провайдер не сообщил и в конфиге нет)
     * @param used      использовано в текущем окне (минута — скользящая, сутки/месяц — по UTC)
     * @param remaining остаток (для TPM — с учётом x-ratelimit-remaining-tokens), null — без ограничения
     * @param source    api | headers | config, null — лимита нет
     */
    public record LimitUsage(Long limit, long used, Long remaining, ru.akiev.gateway.limits.LimitSource source) {
    }

    /** Все лимиты провайдера. */
    public record LimitsView(LimitUsage rpm, LimitUsage rpd, LimitUsage tpm,
            @JsonProperty("tokens_per_day") LimitUsage tokensPerDay,
            @JsonProperty("tokens_per_month") LimitUsage tokensPerMonth) {
    }

    /** Итог последнего опроса лимитов. */
    public record ProbeView(boolean ok, String method, @JsonProperty("http_status") Integer httpStatus, String error,
            @JsonProperty("latency_ms") long latencyMs, @JsonProperty("checked_at") Instant checkedAt) {
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
