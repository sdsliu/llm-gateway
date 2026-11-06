package ru.akiev.gateway.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.akiev.gateway.api.generated.api.StatsApi;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Статистика эффективности (issue #33).
 * <pre>
 * GET /stats/runs?groupBy=run|issue|client&amp;days=30&amp;limit=200  — агрегаты шлюза по меткам запроса
 * GET /stats/runs?runId=a,b                                    — те же агрегаты только по этим run_id, без окна days
 * GET /stats/runs/{runId}/requests?attempt=                    — запросы одного прогона (до 2000, по времени)
 * GET /stats/efficiency?days=30                                — то же по run_id + итоги ai-runner (PR, попытки, успех)
 * </pre>
 * Страница: /efficiency.html.
 *
 * <p>API first: пути и query-параметры (с их значениями по умолчанию) — из сгенерированного {@link StatsApi}.
 * Параметры приходят как {@code Integer}/{@code String}: defaultValue в интерфейсе гарантирует, что они не null.
 */
@RestController
public class StatsController implements StatsApi {

    /** Столько строк отдаёт список запросов прогона; остальные (более поздние) не входят. */
    static final int MAX_REQUESTS = 2000;

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

    @Override
    public ResponseEntity<List<RunStats>> getRunStats(String groupBy, Integer days, Integer limit, String runId) {
        RunStatsAggregator.GroupBy g;
        try {
            g = RunStatsAggregator.GroupBy.parse(groupBy);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy: run | issue | client");
        }
        return ResponseEntity.ok(aggregator.aggregate(recordsFor(days, runId), g, Math.max(1, Math.min(limit, 1000))));
    }

    @Override
    public ResponseEntity<RunRequests> getRunRequests(String runId, Integer attempt) {
        List<UsageRecord> rows = repository.findByRunIdOrderByCreatedAtAscIdAsc(runId);
        if (attempt != null) {
            rows = rows.stream().filter(r -> attempt.equals(r.getAttempt())).toList();
        }
        if (rows.size() > MAX_REQUESTS) {
            rows = rows.subList(0, MAX_REQUESTS);
        }
        return ResponseEntity.ok(RunRequests.of(runId, rows));
    }

    @Override
    public ResponseEntity<EfficiencyMerger.Report> getEfficiency(Integer days) {
        // Только помеченные run_id: без метки прогон не с чем сопоставить
        List<RunStats> byRun = aggregator.aggregate(
                records(days).stream().filter(r -> r.getRunId() != null).toList(), RunStatsAggregator.GroupBy.RUN, 1000);
        RunnerStatsClient.Result r = runner.fetch();
        return ResponseEntity.ok(merger.merge(byRun, r.runs(), r.error()));
    }

    /**
     * Непустой {@code runId} (список через запятую) — только эти прогоны и вся их история.
     * Пустой — окно {@code days}, как раньше.
     */
    private List<UsageRecord> recordsFor(int days, String runId) {
        List<String> ids = runIds(runId);
        if (!ids.isEmpty()) {
            return repository.findByRunIdIn(ids);
        }
        return records(days);
    }

    private List<UsageRecord> records(int days) {
        int d = Math.max(1, Math.min(days, 365));
        return repository.findByCreatedAtAfterOrderByCreatedAtAsc(clock.instant().minus(Duration.ofDays(d)));
    }

    /** {@code "a, b,, a"} → [a, b]. Пустое и пробелы — пустой список. */
    static List<String> runIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.strip();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }
}
