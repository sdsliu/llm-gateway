package ru.akiev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RateLimitHeadersTest {

    final Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    void groqHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("x-ratelimit-remaining-requests", "14370");
        h.add("x-ratelimit-remaining-tokens", "5997");
        h.add("x-ratelimit-reset-requests", "2m59.56s");
        assertThat(RateLimitHeaders.remainingRequests(h)).isEqualTo(14370);
        assertThat(RateLimitHeaders.remainingTokens(h)).isEqualTo(5997);
        assertThat(RateLimitHeaders.resetRequests(h, now)).isEqualTo(Duration.ofMillis(179_560));
    }

    @Test
    void openRouterHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-RateLimit-Remaining", "3");
        h.add("X-RateLimit-Reset", String.valueOf(now.plusSeconds(30).toEpochMilli()));
        assertThat(RateLimitHeaders.remainingRequests(h)).isEqualTo(3);
        assertThat(RateLimitHeaders.resetRequests(h, now)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterSecondsAndDate() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "42");
        assertThat(RateLimitHeaders.retryAfter(h, now)).isEqualTo(Duration.ofSeconds(42));
        HttpHeaders d = new HttpHeaders();
        d.add("Retry-After", "Wed, 14 Oct 2026 12:01:00 GMT");
        assertThat(RateLimitHeaders.retryAfter(d, now)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RateLimitHeaders.retryAfter(new HttpHeaders(), now)).isNull();
    }

    @Test
    void goDurationVariants() {
        assertThat(RateLimitHeaders.parseGoDuration("120ms")).isEqualTo(Duration.ofMillis(120));
        assertThat(RateLimitHeaders.parseGoDuration("1h2m")).isEqualTo(Duration.ofMinutes(62));
        assertThat(RateLimitHeaders.parseGoDuration("garbage")).isNull();
    }
}
