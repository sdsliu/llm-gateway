package ru.akiev.gateway.stats;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Агрегат попыток одной группы (прогон / issue / клиент) для GET /stats/runs.
 *
 * @param key         значение группировки (run_id, issue или client); null — «запросы без метки»
 * @param requests    попыток к провайдерам (включая неуспешные и failover)
 * @param errors      из них неуспешных (не 2xx)
 * @param providers   провайдер → число попыток
 * @param durationMs  от первой до последней попытки (грубая оценка длительности задачи)
 * @param cost        оценка стоимости на платных моделях (см. {@link PricingProperties})
 */
public record RunStats(
        String key,
        @JsonProperty("run_ids") List<String> runIds,
        List<String> issues,
        List<String> clients,
        long requests,
        long errors,
        @JsonProperty("prompt_tokens") long promptTokens,
        @JsonProperty("completion_tokens") long completionTokens,
        @JsonProperty("total_tokens") long totalTokens,
        Map<String, Long> providers,
        @JsonProperty("first_at") Instant firstAt,
        @JsonProperty("last_at") Instant lastAt,
        @JsonProperty("duration_ms") long durationMs,
        @JsonProperty("latency_ms") long latencyMs,
        List<CostEstimate> cost) {

    /** Оценка стоимости на одной справочной модели. */
    public record CostEstimate(String model, BigDecimal amount) {
    }
}
