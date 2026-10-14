package ru.akiev.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LimitTrackerTest {

    final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    void rpmWindowSlides() {
        LimitTracker t = new LimitTracker(2, null);
        t.increment(t0);
        t.increment(t0.plusSeconds(10));
        assertThat(t.allows(t0.plusSeconds(20))).isFalse();
        assertThat(t.lastMinute(t0.plusSeconds(20))).isEqualTo(2);
        assertThat(t.allows(t0.plusSeconds(61))).isTrue();
        assertThat(t.lastMinute(t0.plusSeconds(61))).isEqualTo(1);
    }

    @Test
    void rpdWindowSlides() {
        LimitTracker t = new LimitTracker(null, 3);
        for (int i = 0; i < 3; i++) {
            t.increment(t0.plus(Duration.ofHours(i)));
        }
        assertThat(t.allows(t0.plus(Duration.ofHours(5)))).isFalse();
        assertThat(t.lastDay(t0.plus(Duration.ofHours(24)).plusSeconds(1))).isEqualTo(2);
        assertThat(t.allows(t0.plus(Duration.ofHours(24)).plusSeconds(1))).isTrue();
    }

    @Test
    void noLimitsAlwaysAllows() {
        LimitTracker t = new LimitTracker(null, 0);
        for (int i = 0; i < 100; i++) {
            t.increment(t0);
        }
        assertThat(t.allows(t0)).isTrue();
    }
}
