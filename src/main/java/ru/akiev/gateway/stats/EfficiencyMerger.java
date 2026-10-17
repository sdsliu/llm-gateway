package ru.akiev.gateway.stats;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Склейка двух источников по run_id (чистая функция, без сети):
 * <ul>
 *   <li>шлюз знает, сколько запросов/токенов/ошибок потратил прогон ({@link RunStats});</li>
 *   <li>ai-runner знает, чем прогон кончился: статус, попытки, длительность, PR ({@code GET /stats} spring-runner).</li>
 * </ul>
 * Прогоны, которых нет у ai-runner (Project Sage, UI Tester, ручные запросы с x-run-id), идут в {@code other}.
 */
public class EfficiencyMerger {

    /** Строка таблицы «Эффективность». Поля ai-runner пустые, если runner не ответил. */
    public record Row(
            @JsonProperty("run_id") String runId,
            String issue,
            String repo,
            String status,
            Boolean success,
            Integer attempts,
            @JsonProperty("duration_ms") Long durationMs,
            @JsonProperty("pr_url") String prUrl,
            String category,
            long requests,
            long errors,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("aider_tokens") Long aiderTokens,
            List<RunStats.CostEstimate> cost) {
    }

    /** Сводка по завершённым прогонам ai-runner. */
    public record Summary(
            long runs,
            long succeeded,
            @JsonProperty("success_rate") Double successRate,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("tokens_per_success") Long tokensPerSuccess,
            @JsonProperty("avg_attempts") Double avgAttempts,
            List<RunStats.CostEstimate> cost) {
    }

    /** Весь ответ GET /stats/efficiency. */
    public record Report(
            @JsonProperty("runner_available") boolean runnerAvailable,
            @JsonProperty("runner_error") String runnerError,
            @JsonProperty("pricing_note") String pricingNote,
            String currency,
            Summary summary,
            List<Row> runs,
            List<RunStats> other) {
    }

    private static final java.util.Set<String> UNFINISHED = java.util.Set.of("QUEUED", "RUNNING", "AWAITING_CONFIRMATION");

    private final RunStatsAggregator aggregator;
    private final PricingProperties pricing;

    public EfficiencyMerger(RunStatsAggregator aggregator, PricingProperties pricing) {
        this.aggregator = aggregator;
        this.pricing = pricing;
    }

    /**
     * @param gateway     агрегаты шлюза, сгруппированные по run_id
     * @param runnerRuns  массив {@code runs} из GET {runner}/stats; null — runner недоступен
     * @param runnerError почему runner недоступен (для подписи на странице)
     */
    public Report merge(List<RunStats> gateway, JsonNode runnerRuns, String runnerError) {
        Map<String, RunStats> byRun = gateway.stream().filter(s -> s.key() != null)
                .collect(Collectors.toMap(RunStats::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Row> rows = new ArrayList<>();
        long runs = 0, ok = 0, tokens = 0, attempts = 0, prompt = 0, completion = 0;

        if (runnerRuns != null) {
            for (JsonNode r : runnerRuns) {
                String id = r.path("runId").asText(null);
                RunStats g = id == null ? null : byRun.remove(id);
                String status = r.path("status").asText(null);
                boolean success = "SUCCESS".equals(status);
                Long aider = r.path("aiderTokens").isNumber() ? r.path("aiderTokens").asLong() : null;
                // Токены: по шлюзу (точно, по x-run-id), иначе — то, что насчитал Aider
                long tok = g != null ? g.totalTokens() : aider != null ? aider : 0;
                rows.add(new Row(id, text(r, "issue"), text(r, "repo"), status, success,
                        r.path("attempts").isNumber() ? r.path("attempts").asInt() : null,
                        r.path("durationMs").isNumber() ? r.path("durationMs").asLong() : null,
                        text(r, "prUrl"), text(r, "category"),
                        g != null ? g.requests() : 0, g != null ? g.errors() : 0, tok, aider,
                        g != null ? g.cost() : List.of()));
                if (status != null && !UNFINISHED.contains(status)) {
                    runs++;
                    if (success) ok++;
                    tokens += tok;
                    attempts += r.path("attempts").asLong(0);
                    if (g != null) {
                        prompt += g.promptTokens();
                        completion += g.completionTokens();
                    }
                }
            }
        }
        Summary summary = new Summary(runs, ok, runs == 0 ? null : (double) ok / runs, tokens,
                ok == 0 ? null : tokens / ok, runs == 0 ? null : (double) attempts / runs,
                aggregator.cost(prompt, completion));
        // Оставшиеся группы шлюза с run_id — прогоны других агентов
        List<RunStats> other = new ArrayList<>(byRun.values());
        return new Report(runnerRuns != null, runnerError, pricing.note(), pricing.currency(), summary, rows, other);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
