package ru.akiev.gateway.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Статистика эффективности (issue #33).
 * <pre>
 * GET /stats/runs?groupBy=run|issue|client&amp;days=30&amp;limit=200  — агрегаты шлюза по меткам запроса
 * GET /stats/efficiency?days=30                                — то же по run_id + итоги ai-runner (PR, попытки, успех)
 * </pre>
 * Страница: /efficiency.html.
 */
@RestController
public class StatsController {

    private final UsageRecordRepository repository;
    private final RunStatsAggregator aggregator;
    private final EfficiencyMerger merger;
    private final RunnerStatsClient runner;
    private final Clock clock;

    public StatsController(UsageRecordRepository repository, PricingProperties pricing, Clock clock, ObjectMapper mapper,
            @Value("${gateway.efficiency.runner-stats-url:}") String runnerStatsUrl) {
        this.repository = repository;
        this.aggregator = new RunStatsAggregator(pricing);
        this.merger = new EfficiencyMerger(aggregator, pricing);
        this.runner = new RunnerStatsClient(runnerStatsUrl, mapper);
        this.clock = clock;
    }

    @GetMapping("/stats/runs")
    public List<RunStats> runs(@RequestParam(defaultValue = "run") String groupBy,
            @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "200") int limit) {
        RunStatsAggregator.GroupBy g;
        try {
            g = RunStatsAggregator.GroupBy.parse(groupBy);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy: run | issue | client");
        }
        return aggregator.aggregate(records(days), g, Math.max(1, Math.min(limit, 1000)));
    }

    @GetMapping("/stats/efficiency")
    public EfficiencyMerger.Report efficiency(@RequestParam(defaultValue = "30") int days) {
        // Только помеченные run_id: без метки прогон не с чем сопоставить
        List<RunStats> byRun = aggregator.aggregate(
                records(days).stream().filter(r -> r.getRunId() != null).toList(), RunStatsAggregator.GroupBy.RUN, 1000);
        RunnerStatsClient.Result r = runner.fetch();
        return merger.merge(byRun, r.runs(), r.error());
    }

    private List<UsageRecord> records(int days) {
        int d = Math.max(1, Math.min(days, 365));
        return repository.findByCreatedAtAfterOrderByCreatedAtAsc(clock.instant().minus(Duration.ofDays(d)));
    }
}
