package ru.akiev.gateway.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.akiev.gateway.usage.RequestTags;
import ru.akiev.gateway.usage.UsageRecord;

/** Агрегация и склейка с ai-runner — чистые функции, без Spring и сети. */
class RunStatsAggregatorTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    static final PricingProperties PRICING = new PricingProperties("USD", "оценка", List.of(
            new PricingProperties.ModelPrice("paid-model", new BigDecimal("2.00"), new BigDecimal("10.00"))));

    final RunStatsAggregator agg = new RunStatsAggregator(PRICING);

    static UsageRecord rec(String provider, int status, Integer p, Integer c, int sec, RequestTags tags) {
        return new UsageRecord(provider, "m", p, c, p == null ? null : p + c, status, 100, status == 200 ? null : "err",
                T0.plusSeconds(sec), tags);
    }

    static RequestTags run(String id) {
        return new RequestTags(id, "42", "ai-runner", null);
    }

    @Test
    void groupsByRunAndSumsTokensErrorsProvidersAndDuration() {
        List<UsageRecord> rs = List.of(
                rec("groq", 200, 1000, 200, 0, run("r1")),
                rec("groq", 429, null, null, 5, run("r1")),
                rec("kilo", 200, 3000, 800, 65, run("r1")),
                rec("groq", 200, 10, 5, 100, new RequestTags(null, null, null, "project-sage")),
                rec("groq", 200, 100, 50, 30, run("r2")));

        List<RunStats> out = agg.aggregate(rs, RunStatsAggregator.GroupBy.RUN, 10);

        // Сортировка: новые сверху; «без метки» (key=null) — отдельная группа
        assertThat(out).extracting(RunStats::key).containsExactly(null, "r1", "r2");
        RunStats r1 = out.get(1);
        assertThat(r1.requests()).isEqualTo(3);
        assertThat(r1.errors()).isEqualTo(1);
        assertThat(r1.promptTokens()).isEqualTo(4000);
        assertThat(r1.completionTokens()).isEqualTo(1000);
        assertThat(r1.totalTokens()).isEqualTo(5000);
        assertThat(r1.providers()).containsEntry("groq", 2L).containsEntry("kilo", 1L);
        assertThat(r1.durationMs()).isEqualTo(65_000);
        assertThat(r1.issues()).containsExactly("42");
        // 4000/1M × 2 + 1000/1M × 10 = 0.008 + 0.01 = 0.018
        assertThat(r1.cost()).singleElement().satisfies(c -> {
            assertThat(c.model()).isEqualTo("paid-model");
            assertThat(c.amount()).isEqualByComparingTo("0.018");
        });
    }

    @Test
    void groupByClientFallsBackToBodyUser() {
        List<UsageRecord> rs = List.of(rec("groq", 200, 1, 1, 0, run("r1")),
                rec("groq", 200, 1, 1, 1, new RequestTags(null, null, null, "project-sage")));
        assertThat(agg.aggregate(rs, RunStatsAggregator.GroupBy.CLIENT, 10))
                .extracting(RunStats::key).containsExactlyInAnyOrder("ai-runner", "project-sage");
    }

    @Test
    void mergesWithRunnerOutcomesAndComputesSuccessRate() throws Exception {
        List<RunStats> byRun = agg.aggregate(List.of(
                rec("groq", 200, 1000, 200, 0, run("r1")),
                rec("groq", 200, 500, 100, 1, run("r2")),
                rec("groq", 200, 50, 10, 2, new RequestTags("sage-1", null, "project-sage", null))),
                RunStatsAggregator.GroupBy.RUN, 10);
        var runner = new ObjectMapper().readTree("""
                [{"runId":"r1","issue":42,"repo":"sdsliu/app","status":"SUCCESS","attempts":1,"durationMs":60000,
                  "prUrl":"https://github.com/sdsliu/app/pull/5"},
                 {"runId":"r2","issue":43,"status":"FAILED","attempts":3,"category":"TESTS_FAILED"},
                 {"runId":"r3","issue":44,"status":"RUNNING","attempts":1,"aiderTokens":777}]""");

        var report = new EfficiencyMerger(agg, PRICING).merge(byRun, runner, null);

        assertThat(report.runnerAvailable()).isTrue();
        assertThat(report.runs()).hasSize(3);
        assertThat(report.runs().get(0).totalTokens()).isEqualTo(1200);
        assertThat(report.runs().get(0).prUrl()).endsWith("/pull/5");
        assertThat(report.runs().get(2).totalTokens()).as("нет данных шлюза — токены Aider").isEqualTo(777);
        // Незавершённый r3 в сводку не входит: 1 успех из 2
        assertThat(report.summary().runs()).isEqualTo(2);
        assertThat(report.summary().successRate()).isEqualTo(0.5);
        assertThat(report.summary().tokensPerSuccess()).isEqualTo(1800);
        assertThat(report.other()).extracting(RunStats::key).containsExactly("sage-1");
    }

    @Test
    void runnerUnavailableGivesGatewayOnlyReport() {
        List<RunStats> byRun = agg.aggregate(List.of(rec("groq", 200, 1, 1, 0, run("r1"))), RunStatsAggregator.GroupBy.RUN, 10);
        var report = new EfficiencyMerger(agg, PRICING).merge(byRun, null, "ai-runner недоступен");
        assertThat(report.runnerAvailable()).isFalse();
        assertThat(report.runnerError()).contains("недоступен");
        assertThat(report.runs()).isEmpty();
        assertThat(report.summary().successRate()).isNull();
        assertThat(report.other()).hasSize(1);
    }

    @Test
    void requestTagsAreTrimmedAndBlankIsNull() {
        RequestTags t = new RequestTags("  r1 \n", "", "x".repeat(100), null);
        assertThat(t.runId()).isEqualTo("r1");
        assertThat(t.issue()).isNull();
        assertThat(t.client()).hasSize(64);
        try (var s = RequestTags.bind(t)) {
            assertThat(RequestTags.current().runId()).isEqualTo("r1");
        }
        assertThat(RequestTags.current()).isEqualTo(RequestTags.NONE);
    }
}
