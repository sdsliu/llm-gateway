package ru.akiev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.provider.ProviderState;

/** Счётчики токенов: скользящая минута, сутки/месяц, уточнение фактом и заголовками; оценка запроса. */
class TokenBudgetTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void minuteWindowSlidesAndSettleReplacesEstimate() {
        TokenBudget b = new TokenBudget();
        b.setLimits(1000L, null, null);
        var r = b.reserve(T0, 800);
        assertThat(b.check(T0, 300)).isNotNull().extracting(TokenBudget.Block::reason).isEqualTo("tpm");
        // ответ пришёл: на самом деле потрачено 100 — освободилось место
        b.settle(r, 100L);
        assertThat(b.check(T0, 300)).isNull();
        assertThat(b.minuteTokens(T0)).isEqualTo(100);
        assertThat(b.dayTokens(T0)).isEqualTo(100);
        // через минуту резервация выходит из окна
        assertThat(b.minuteTokens(T0.plusSeconds(61))).isZero();
        assertThat(b.dayTokens(T0.plusSeconds(61))).isEqualTo(100);
    }

    @Test
    void retryAtIsWhenEnoughOldReservationsLeaveTheWindow() {
        TokenBudget b = new TokenBudget();
        b.setLimits(1000L, null, null);
        b.reserve(T0, 600);
        b.reserve(T0.plusSeconds(10), 300);
        TokenBudget.Block block = b.check(T0.plusSeconds(20), 500);
        assertThat(block.retryAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(block.neverFits()).isFalse();
        assertThat(b.check(T0, 1001).neverFits()).isTrue();
    }

    @Test
    void dayAndMonthLimitsAndRollover() {
        TokenBudget b = new TokenBudget();
        b.setLimits(null, 1000L, 1500L);
        b.reserve(T0, 900);
        assertThat(b.check(T0, 200).reason()).isEqualTo("tokens_per_day");
        assertThat(b.check(T0, 200).retryAt()).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        Instant tomorrow = Instant.parse("2026-10-18T09:00:00Z");
        assertThat(b.dayTokens(tomorrow)).isZero();
        assertThat(b.monthTokens(tomorrow)).isEqualTo(900);
        assertThat(b.check(tomorrow, 700).reason()).isEqualTo("tokens_per_month");
        assertThat(b.monthTokens(Instant.parse("2026-11-01T00:00:01Z"))).isZero();
    }

    @Test
    void headerRemainingCapsMinuteBudgetUntilReset() {
        TokenBudget b = new TokenBudget();
        b.setLimits(8000L, null, null);
        b.observeRemaining(T0, 500, T0.plusSeconds(30));
        assertThat(b.minuteRemaining(T0)).isEqualTo(500);
        b.reserve(T0.plusSeconds(1), 200);
        assertThat(b.minuteRemaining(T0.plusSeconds(2))).isEqualTo(300);
        assertThat(b.check(T0.plusSeconds(2), 400).retryAt()).isEqualTo(T0.plusSeconds(30));
        // после сброса — снова свой счёт: 8000 - 200
        assertThat(b.minuteRemaining(T0.plusSeconds(31))).isEqualTo(7800);
    }

    @Test
    void headroomIsMinimumOverKnownLimits() {
        ProviderState s = new ProviderState(new ProviderProperties.Limits(10, null, 1000, null, null));
        // tpm: (1000 - 500) / 1000 = 0.5; rpm: (10 - 0 - 1) / 10 = 0.9 → 0.5
        assertThat(s.capacity(T0, 500).headroom()).isEqualTo(0.5);
        assertThat(new ProviderState(null).capacity(T0, 500).headroom()).isEqualTo(1.0);
    }

    @Test
    void effectiveLimitsPreferHeadersThenApiThenConfig() {
        var config = new DiscoveredLimits(30L, 1000L, 8000L, null, null);
        var api = new DiscoveredLimits(20L, null, null, null, null);
        var headers = new DiscoveredLimits(null, 14400L, null, null, null);
        EffectiveLimits e = EffectiveLimits.resolve(config, api, headers);
        assertThat(e.rpm()).isEqualTo(new EffectiveLimits.LimitValue(20L, LimitSource.API));
        assertThat(e.rpd()).isEqualTo(new EffectiveLimits.LimitValue(14400L, LimitSource.HEADERS));
        assertThat(e.tpm()).isEqualTo(new EffectiveLimits.LimitValue(8000L, LimitSource.CONFIG));
        assertThat(e.tokensPerDay().value()).isNull();
        assertThat(e.toString()).contains("rpm=20 (api)", "rpd=14400 (headers)", "tpm=8000 (config)");

        ProviderState s = new ProviderState(new ProviderProperties.Limits(30, 1000, 8000, null, null));
        assertThat(s.applyHeaderLimits(headers)).isTrue();
        assertThat(s.limits().rpd()).isEqualTo(14400);
        assertThat(s.applyHeaderLimits(headers)).isFalse();
    }

    @Test
    void estimateIsInputPlusMaxTokens() {
        RequestEstimator est = new RequestEstimator(1024);
        var withMax = new ChatRequest("m", List.of(new Message("user", "x".repeat(400))), false, null, null, 100,
                null, null, null, null);
        // 400 символов / 4 = 100 + 4 служебных на сообщение + max_tokens 100
        assertThat(est.estimate(withMax).total()).isEqualTo(204);
        var noMax = new ChatRequest("m", List.of(new Message("user", "hi")), false, null, null, null,
                null, null, null, null);
        assertThat(est.estimate(noMax).output()).isEqualTo(1024);
    }

    @Test
    void excludedProviderIsNotAvailable() {
        ProviderState s = new ProviderState(null);
        s.exclude(T0, "нет ответа");
        assertThat(s.isAvailable(T0)).isFalse();
        assertThat(s.blockedBy(T0)).isEqualTo("excluded");
        s.include();
        assertThat(s.isAvailable(T0)).isTrue();
        s.pauseAfter429(T0, T0.plus(Duration.ofMinutes(5)), "HTTP 429");
        assertThat(s.pausedBy429At(T0.plusSeconds(1))).isEqualTo(T0);
        assertThat(s.pausedBy429At(T0.plus(Duration.ofMinutes(6)))).isNull();
    }
}
