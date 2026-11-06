package ru.akiev.gateway.stats;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.akiev.gateway.usage.UsageRecord;

/**
 * Ответ {@code GET /stats/runs/{runId}/requests}.
 *
 * <p>Итоги считаются по строкам, которые реально попали в ответ (после фильтра {@code attempt} и лимита).
 * NULL в новых колонках старых строк остаются null и в JSON не попадают ({@code non_null}).
 */
public record RunRequests(
        @JsonProperty("run_id") String runId,
        List<Item> requests,
        Totals totals) {

    /** Сколько символов {@code error} отдаём наружу. В БД строка может быть длиннее (до 1000). */
    static final int ERROR_MAX = 300;

    public static RunRequests of(String runId, List<UsageRecord> rows) {
        List<Item> items = new ArrayList<>(rows.size());
        long errors = 0, prompt = 0, completion = 0, maxPrompt = 0;
        Map<String, Long> byProvider = new LinkedHashMap<>();
        Map<String, Long> byAttempt = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        for (UsageRecord r : rows) {
            items.add(Item.from(r));
            if (!r.isSuccess()) {
                errors++;
            }
            prompt += nz(r.getPromptTokens());
            completion += nz(r.getCompletionTokens());
            Integer ctx = r.contextTokens();
            if (ctx != null) {
                maxPrompt = Math.max(maxPrompt, ctx.longValue());
            }
            if (r.getProvider() != null) {
                byProvider.merge(r.getProvider(), 1L, Long::sum);
            }
            if (r.getAttempt() != null) {
                byAttempt.merge(Integer.toString(r.getAttempt()), 1L, Long::sum);
            }
        }
        return new RunRequests(runId, List.copyOf(items), new Totals(rows.size(), errors, prompt, completion,
                maxPrompt, Map.copyOf(byProvider), Map.copyOf(byAttempt)));
    }

    /** Одна попытка к провайдеру. Имена полей — как в контракте (snake_case). */
    public record Item(
            Long id,
            @JsonProperty("request_id") String requestId,
            Integer attempt,
            @JsonProperty("failover_index") Integer failoverIndex,
            @JsonProperty("created_at") Instant createdAt,
            String provider,
            String model,
            int status,
            @JsonProperty("latency_ms") long latencyMs,
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens,
            @JsonProperty("est_prompt_tokens") Integer estPromptTokens,
            @JsonProperty("messages_count") Integer messagesCount,
            @JsonProperty("request_chars") Long requestChars,
            @JsonProperty("max_tokens") Integer maxTokens,
            Boolean stream,
            String error) {

        static Item from(UsageRecord r) {
            return new Item(r.getId(), r.getRequestId(), r.getAttempt(), r.getFailoverIndex(), r.getCreatedAt(),
                    r.getProvider(), r.getModel(), r.getStatus(), r.getLatencyMs(),
                    r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens(),
                    r.getEstPromptTokens(), r.getMessagesCount(), r.getRequestChars(),
                    r.getMaxTokens(), r.getStream(), truncate(r.getError()));
        }

        static String truncate(String error) {
            if (error == null || error.length() <= ERROR_MAX) {
                return error;
            }
            return error.substring(0, ERROR_MAX);
        }
    }

    /** Итоги по отданным строкам. */
    public record Totals(
            long requests,
            long errors,
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens,
            @JsonProperty("max_prompt_tokens") long maxPromptTokens,
            @JsonProperty("by_provider") Map<String, Long> byProvider,
            @JsonProperty("by_attempt") Map<String, Long> byAttempt) {
    }

    private static long nz(Integer v) {
        return v == null ? 0 : v;
    }
}
