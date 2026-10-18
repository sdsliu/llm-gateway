package ru.akiev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.akiev.gateway.LlmGatewayApplication;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Счётчики запросов и токенов переживают рестарт: каждый «запуск» — отдельный Spring-контекст поверх одной
 * H2-базы (как Postgres между рестартами шлюза). Записи usage_record делаем напрямую через репозиторий.
 */
class CounterRestoreTest {

    final String dbUrl = "jdbc:h2:mem:restore-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(LlmGatewayApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + dbUrl,
                "--gateway.providers[0].name=alpha", "--gateway.providers[0].base-url=http://localhost:1/v1",
                "--gateway.providers[0].api-key=k", "--gateway.providers[0].model=m",
                "--gateway.providers[0].limits.tokens-per-day=100000",
                "--gateway.providers[0].limits.tpm=8000");
    }

    static LlmProvider alpha(ConfigurableApplicationContext ctx) {
        return ctx.getBean(ProviderRouter.class).providers().get(0);
    }

    @Test
    void dayMonthMinuteAndRequestCountersAreRestoredFromUsageRecord() {
        Instant now = Instant.now();
        Instant monthStart = LocalDate.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<UsageRecord> records = List.of(
                new UsageRecord("alpha", "m", 100, 200, 300, 200, 10, null, now.minusSeconds(5)),
                new UsageRecord("alpha", "m", null, null, null, 429, 10, "429", now.minusSeconds(4)),
                new UsageRecord("alpha", "m", 1000, 1000, 2000, 200, 10, null, now.minusSeconds(3 * 3600)),
                new UsageRecord("alpha", "m", 5000, 5000, 10000, 200, 10, null, now.minusSeconds(3 * 86400)),
                new UsageRecord("other", "m", 1, 1, 2, 200, 10, null, now.minusSeconds(5)));
        try (var ctx = start()) {
            ctx.getBean(UsageRecordRepository.class).saveAll(records);
            assertThat(alpha(ctx).state().budget().dayTokens(now)).isZero(); // до рестарта счётчики пустые
        }
        try (var ctx = start()) {
            LlmProvider p = alpha(ctx);
            Instant t = Instant.now();
            long expectedDay = records.stream().filter(r -> r.getProvider().equals("alpha") && r.getTotalTokens() != null
                    && !r.getCreatedAt().isBefore(dayStart)).mapToLong(UsageRecord::getTotalTokens).sum();
            long expectedMonth = records.stream().filter(r -> r.getProvider().equals("alpha") && r.getTotalTokens() != null
                    && !r.getCreatedAt().isBefore(monthStart)).mapToLong(UsageRecord::getTotalTokens).sum();
            long expectedDayRequests = records.stream().filter(r -> r.getProvider().equals("alpha")
                    && r.getCreatedAt().isAfter(t.minusSeconds(86400))).count();
            assertThat(p.state().budget().dayTokens(t)).isEqualTo(expectedDay);
            assertThat(p.state().budget().monthTokens(t)).isEqualTo(expectedMonth);
            assertThat(p.state().budget().minuteTokens(t)).isEqualTo(300);
            assertThat(p.state().limits().lastDay(t)).isEqualTo(expectedDayRequests);
            assertThat(p.state().limits().lastMinute(t)).isEqualTo(2);
            assertThat(p.state().effectiveLimits().tokensPerDay())
                    .isEqualTo(new EffectiveLimits.LimitValue(100000L, LimitSource.CONFIG));
        }
    }
}
