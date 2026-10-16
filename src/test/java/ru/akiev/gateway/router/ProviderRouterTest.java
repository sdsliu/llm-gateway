package ru.akiev.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.provider.ProviderException;

/** Интеграционные тесты роутера: два «провайдера» = два MockWebServer. */
class ProviderRouterTest {

    static String ok(String text) {
        return """
                {"id":"x","object":"chat.completion","created":1,"model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.formatted(text);
    }

    /** Ручные часы: тест сам двигает время вперёд. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T12:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    MockWebServer primary;
    MockWebServer secondary;
    MutableClock clock;
    LlmProvider p1;
    LlmProvider p2;
    ProviderRouter router;

    @BeforeEach
    void setUp() throws Exception {
        primary = new MockWebServer();
        secondary = new MockWebServer();
        primary.start();
        secondary.start();
        clock = new MutableClock();
        p1 = provider("primary", primary, 1, Duration.ofSeconds(5), null);
        p2 = provider("secondary", secondary, 2, Duration.ofSeconds(5), null);
        // специально передаём в «неправильном» порядке — роутер должен отсортировать
        router = new ProviderRouter(List.of(p2, p1), Duration.ofMinutes(5), clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        primary.shutdown();
        secondary.shutdown();
    }

    LlmProvider provider(String name, MockWebServer s, int prio, Duration timeout, ProviderProperties.Limits limits) {
        return new OpenAiCompatibleProvider(WebClient.builder(),
                new ProviderProperties(name, s.url("/v1").toString(), "k-" + name, "m-" + name, prio, timeout, limits, true, "/models"),
                clock);
    }

    ChatRequest req() {
        return new ChatRequest("openai/coder", List.of(new Message("user", "hi")), null, null, null, null, null,
                null, null, null);
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    String answer() {
        return router.chat(req()).choices().get(0).message().content();
    }

    @Test
    void picksHighestPriorityWhenAllHealthy() {
        primary.enqueue(json(ok("from-primary")));
        assertThat(answer()).isEqualTo("from-primary");
        assertThat(secondary.getRequestCount()).isZero();
    }

    @Test
    void failoverOn429AndCooldownFromRetryAfter() {
        primary.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
        secondary.enqueue(json(ok("from-secondary")));
        secondary.enqueue(json(ok("from-secondary-2")));
        primary.enqueue(json(ok("primary-back")));

        assertThat(answer()).isEqualTo("from-secondary");
        assertThat(p1.state().cooldownUntil()).isEqualTo(clock.now.plusSeconds(60));

        // В cooldown primary не трогаем
        clock.advance(Duration.ofSeconds(30));
        assertThat(answer()).isEqualTo("from-secondary-2");
        assertThat(primary.getRequestCount()).isEqualTo(1);

        // Cooldown истёк — снова primary
        clock.advance(Duration.ofSeconds(31));
        assertThat(answer()).isEqualTo("primary-back");
    }

    @Test
    void failoverOn5xxUsesDefaultCooldown() {
        primary.enqueue(new MockResponse().setResponseCode(503));
        secondary.enqueue(json(ok("from-secondary")));
        assertThat(answer()).isEqualTo("from-secondary");
        assertThat(p1.state().cooldownUntil()).isEqualTo(clock.now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void failoverOnTimeout() throws Exception {
        p1 = provider("primary", primary, 1, Duration.ofMillis(300), null);
        router = new ProviderRouter(List.of(p1, p2), Duration.ofMinutes(5), clock);
        primary.enqueue(json(ok("slow")).setHeadersDelay(2, TimeUnit.SECONDS));
        secondary.enqueue(json(ok("fast")));
        assertThat(answer()).isEqualTo("fast");
        assertThat(p1.state().isAvailable(clock.instant())).isFalse();
    }

    @Test
    void allDownGives503Exception() {
        primary.enqueue(new MockResponse().setResponseCode(429));
        secondary.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(this::answer)
                .isInstanceOf(AllProvidersUnavailableException.class)
                .hasMessageContaining("primary: HTTP 429")
                .hasMessageContaining("secondary: HTTP 500");
        // повторный запрос — оба в cooldown, к серверам не ходим
        assertThatThrownBy(this::answer).isInstanceOf(AllProvidersUnavailableException.class);
        assertThat(primary.getRequestCount() + secondary.getRequestCount()).isEqualTo(2);
    }

    @Test
    void badRequestIsNotRetried() {
        primary.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad\"}}"));
        assertThatThrownBy(this::answer)
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).status()).isEqualTo(400));
        assertThat(secondary.getRequestCount()).isZero();
    }

    @Test
    void rateLimitHeadersAreStoredAndZeroRemainingSkipsProvider() {
        primary.enqueue(json(ok("a")).setHeader("x-ratelimit-remaining-requests", "5")
                .setHeader("x-ratelimit-remaining-tokens", "1200"));
        assertThat(answer()).isEqualTo("a");
        assertThat(p1.state().remainingRequests()).isEqualTo(5);
        assertThat(p1.state().remainingTokens()).isEqualTo(1200);

        primary.enqueue(json(ok("b")).setHeader("x-ratelimit-remaining-requests", "0")
                .setHeader("x-ratelimit-reset-requests", "10s"));
        assertThat(answer()).isEqualTo("b");
        assertThat(p1.state().remainingRequests()).isZero();

        secondary.enqueue(json(ok("c")));
        assertThat(answer()).isEqualTo("c");
        assertThat(primary.getRequestCount()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(11));
        primary.enqueue(json(ok("d")));
        assertThat(answer()).isEqualTo("d");
    }

    @Test
    void ownRpmLimitSkipsProvider() {
        p1 = provider("primary", primary, 1, Duration.ofSeconds(5), new ProviderProperties.Limits(1, null));
        router = new ProviderRouter(List.of(p1, p2), Duration.ofMinutes(5), clock);
        primary.enqueue(json(ok("first")));
        secondary.enqueue(json(ok("second")));
        assertThat(answer()).isEqualTo("first");
        assertThat(answer()).isEqualTo("second");
        clock.advance(Duration.ofSeconds(61));
        primary.enqueue(json(ok("third")));
        assertThat(answer()).isEqualTo("third");
    }
}
