package ru.akiev.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.router.RoutingModeTest.Fake;

/**
 * PRIORITY с primary: failover по всем «переключающим» статусам и возврат к primary после конца паузы.
 * Часы управляемые — двигаем время вместо sleep.
 */
class PrimaryFailoverTest {

    /** Часы, которые тест двигает вручную. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-17T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    Fake a;
    Fake b;
    Fake c;
    ProviderRouter router;

    @BeforeEach
    void setUp() {
        a = new Fake("a", 1);
        b = new Fake("b", 2);
        c = new Fake("c", 3);
        router = new ProviderRouter(List.of(a, b, c), Duration.ofMinutes(5), clock);
        router.setRouting(new RoutingSettings(RoutingMode.PRIORITY, "c"));
    }

    ChatRequest req() {
        return new ChatRequest("x", List.of(new Message("user", "hi")), null, null, null, null, null, null, null, null);
    }

    String answer() {
        return router.chat(req()).id();
    }

    String streamAnswer() {
        StreamSession s = router.openStream(req());
        try {
            return s.provider().name();
        } finally {
            s.close();
        }
    }

    /** 401/402/403/429/5xx и 504 (так провайдер отдаёт таймаут) — primary в cooldown, ответ от следующего по priority. */
    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 429, 500, 502, 503, 504})
    void primaryFailsOverToFirstByPriority(int status) {
        c.failWith = status;
        if (status == 504) {
            c.failMessage = "Provider c timed out after PT20S";
        }
        assertThat(answer()).isEqualTo("a");
        assertThat(c.calls).hasValue(1);
        assertThat(a.calls).hasValue(1);
        assertThat(b.calls).hasValue(0);
        assertThat(c.state().isAvailable(clock.instant())).isFalse();
        assertThat(c.state().cooldownReason()).isEqualTo("HTTP " + status);
    }

    @Test
    void primaryNoEndpoints404FailsOver() {
        c.failWith = 404;
        c.failMessage = "{\"error\":{\"message\":\"No endpoints found for nvidia/nemotron:free.\",\"code\":404}}";
        assertThat(answer()).isEqualTo("a");
        assertThat(c.state().isAvailable(clock.instant())).isFalse();
        // и в стриме
        c.state().resetCooldown();
        assertThat(streamAnswer()).isEqualTo("a");
    }

    @Test
    void plain404IsNotFailover() {
        c.failWith = 404;
        c.failMessage = "model not found";
        assertThatThrownBy(this::answer).isInstanceOf(ProviderException.class);
        assertThat(a.calls).hasValue(0);
    }

    @Test
    void primaryAndNextFailGoesFurtherByPriority() {
        c.failWith = 402;
        a.failWith = 429;
        assertThat(answer()).isEqualTo("b");
        assertThat(router.previewOrder().stream().map(p -> p.name()).toList()).containsExactly("c", "a", "b");
    }

    @ParameterizedTest
    @ValueSource(ints = {402, 429, 504})
    void streamingPrimaryFailsOverToFirstByPriority(int status) {
        c.failWith = status;
        assertThat(streamAnswer()).isEqualTo("a");
        assertThat(c.state().isAvailable(clock.instant())).isFalse();
    }

    /** Требование: primary в cooldown → запросы к следующему → после cooldown снова primary. Выбор не сбрасывается. */
    @Test
    void primaryInCooldownThenNextThenPrimaryAgain() {
        c.failWith = 429; // без Retry-After → default cooldown 5 минут
        assertThat(answer()).isEqualTo("a");
        c.failWith = 0;   // лимит у провайдера уже сбросился бы, но шлюз держит паузу

        clock.advance(Duration.ofMinutes(4));
        assertThat(answer()).isEqualTo("a");
        assertThat(streamAnswer()).isEqualTo("a");
        assertThat(c.calls).as("в cooldown к primary не ходим").hasValue(1);
        assertThat(router.routing().primary()).isEqualTo("c");

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(answer()).isEqualTo("c");
        assertThat(streamAnswer()).isEqualTo("c");
        assertThat(c.calls).hasValue(3);
    }

    /** То же для «лимит исчерпан» (remaining = 0 → cooldown до сброса из заголовков) и с Retry-After. */
    @Test
    void primaryLimitExhaustedReturnsAfterReset() {
        c.state().cooldown(clock.instant().plus(Duration.ofMinutes(10)), "remaining requests = 0");
        assertThat(c.state().blockedUntil(clock.instant())).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(List.of(answer(), answer())).containsOnly("a");
        assertThat(c.calls).hasValue(0);
        clock.advance(Duration.ofMinutes(10));
        assertThat(c.state().blockedUntil(clock.instant())).isNull();
        assertThat(answer()).isEqualTo("c");
    }

    @Test
    void primaryWithOwnRpmLimitReturnsWhenWindowSlides() {
        var limits = new ru.akiev.gateway.usage.LimitTracker(2, null);
        Instant t0 = clock.instant();
        limits.increment(t0);
        limits.increment(t0.plusSeconds(10));
        assertThat(limits.allows(t0.plusSeconds(20))).isFalse();
        assertThat(limits.availableAt(t0.plusSeconds(20))).isEqualTo(t0.plusSeconds(60));
        assertThat(limits.availableAt(t0.plusSeconds(60))).isNull();
        var daily = new ru.akiev.gateway.usage.LimitTracker(null, 1);
        daily.increment(t0);
        assertThat(daily.availableAt(t0.plusSeconds(5))).isEqualTo(t0.plus(Duration.ofDays(1)));
    }
}
