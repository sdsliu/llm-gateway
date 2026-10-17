package ru.akiev.gateway.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import ru.akiev.gateway.usage.UsageRecord;

/**
 * Чистая агрегация записей usage_record по прогону / issue / клиенту. Без БД и Spring — легко тестировать.
 * Объёмы личного шлюза небольшие (тысячи записей), поэтому считаем в Java, а не SQL-запросом с GROUP BY:
 * так проще и одинаково работает на PostgreSQL и H2 в тестах.
 */
public class RunStatsAggregator {

    /** По чему группировать. */
    public enum GroupBy {
        RUN(UsageRecord::getRunId), ISSUE(UsageRecord::getIssue), CLIENT(r -> clientOf(r));

        final Function<UsageRecord, String> key;

        GroupBy(Function<UsageRecord, String> key) {
            this.key = key;
        }

        public static GroupBy parse(String s) {
            return valueOf(s == null ? "RUN" : s.strip().toUpperCase(Locale.ROOT));
        }
    }

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final PricingProperties pricing;

    public RunStatsAggregator(PricingProperties pricing) {
        this.pricing = pricing;
    }

    public List<RunStats> aggregate(List<UsageRecord> records, GroupBy groupBy, int limit) {
        // TreeMap не принимает null-ключ, поэтому «без метки» храним под пустой строкой
        Map<String, List<UsageRecord>> groups = new TreeMap<>();
        for (UsageRecord r : records) {
            String k = groupBy.key.apply(r);
            groups.computeIfAbsent(k == null ? "" : k, x -> new ArrayList<>()).add(r);
        }
        return groups.entrySet().stream()
                .map(e -> stats(e.getKey().isEmpty() ? null : e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(RunStats::lastAt).reversed())
                .limit(limit)
                .toList();
    }

    RunStats stats(String key, List<UsageRecord> rs) {
        long errors = 0, prompt = 0, completion = 0, total = 0, latency = 0;
        Set<String> runIds = new LinkedHashSet<>(), issues = new LinkedHashSet<>(), clients = new LinkedHashSet<>();
        Map<String, Long> providers = new LinkedHashMap<>();
        var first = rs.get(0).getCreatedAt();
        var last = first;
        for (UsageRecord r : rs) {
            if (!r.isSuccess()) errors++;
            prompt += nz(r.getPromptTokens());
            completion += nz(r.getCompletionTokens());
            // total_tokens провайдер может не прислать — тогда считаем сами
            total += r.getTotalTokens() != null ? r.getTotalTokens() : nz(r.getPromptTokens()) + nz(r.getCompletionTokens());
            latency += r.getLatencyMs();
            providers.merge(r.getProvider(), 1L, Long::sum);
            addIfPresent(runIds, r.getRunId());
            addIfPresent(issues, r.getIssue());
            addIfPresent(clients, clientOf(r));
            if (r.getCreatedAt().isBefore(first)) first = r.getCreatedAt();
            if (r.getCreatedAt().isAfter(last)) last = r.getCreatedAt();
        }
        return new RunStats(key, List.copyOf(runIds), List.copyOf(issues), List.copyOf(clients), rs.size(), errors,
                prompt, completion, total, providers, first, last, Duration.between(first, last).toMillis(), latency,
                cost(prompt, completion));
    }

    /** Оценка стоимости: tokens / 1M × цена, для каждой справочной модели. */
    public List<RunStats.CostEstimate> cost(long prompt, long completion) {
        List<RunStats.CostEstimate> out = new ArrayList<>();
        for (PricingProperties.ModelPrice p : pricing.modelsOrEmpty()) {
            BigDecimal in = BigDecimal.valueOf(prompt).multiply(nz(p.inputPerMillion()));
            BigDecimal outTok = BigDecimal.valueOf(completion).multiply(nz(p.outputPerMillion()));
            out.add(new RunStats.CostEstimate(p.name(), in.add(outTok).divide(MILLION, 4, RoundingMode.HALF_UP)));
        }
        return out;
    }

    /** Клиент: заголовок x-client, а если его нет — поле user из тела. */
    static String clientOf(UsageRecord r) {
        return r.getClient() != null ? r.getClient() : r.getRequestUser();
    }

    private static long nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void addIfPresent(Set<String> set, String v) {
        if (v != null) set.add(v);
    }
}
