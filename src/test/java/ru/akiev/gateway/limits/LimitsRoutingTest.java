package ru.akiev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RateLimitedException;
import ru.akiev.gateway.router.RequestTooLargeException;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;
import ru.akiev.gateway.usage.RequestTags;

/** Выбор провайдера по лимитам: запас, ожидание, переполнение, 429, модели, привязка к x-run-id (без HTTP). */
class LimitsRoutingTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-17T10:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    /** Провайдер-заглушка: отвечает с usage = usedTokens или падает со статусом failWith. */
    final class Fake implements LlmProvider {
        final String name;
        final int priority;
        final ProviderState state;
        final List<String> models;
        final AtomicInteger calls = new AtomicInteger();
        volatile int failWith;
        volatile int usedTokens = 50;

        Fake(String name, int priority, ProviderProperties.Limits limits, String... models) {
            this.name = name;
            this.priority = priority;
            this.state = new ProviderState(limits);
            this.models = models.length == 0 ? List.of("m-" + name) : List.of(models);
        }

        public String name() { return name; }
        public int priority() { return priority; }
        public ProviderState state() { return state; }
        public List<String> models() { return models; }

        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            state.recordRequest(clock.instant());
            if (failWith != 0) {
                throw new ProviderException(failWith, name + " failed", null);
            }
            return new ChatResponse(name, "chat.completion", 1L, models.get(0), List.of(),
                    new Usage(usedTokens / 2, usedTokens - usedTokens / 2, usedTokens));
        }

        public Flux<String> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            return Flux.just("{\"id\":\"" + name + "\"}");
        }
    }

    MutableClock clock;
    List<Duration> sleeps;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        sleeps = new ArrayList<>();
    }

    static ProviderProperties.Limits tpm(int tpm) {
        return new ProviderProperties.Limits(null, null, tpm, null, null);
    }

    static LimitsProperties settings(Duration maxWait, boolean pin) {
        return new LimitsProperties(false, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(30),
                maxWait, 100, pin, Duration.ofHours(12));
    }

    /** Роутер, который «спит», перематывая фейковые часы (тест не ждёт по-настоящему). */
    ProviderRouter router(LimitsProperties s, RoutingMode mode, LlmProvider... providers) {
        ProviderRouter r = new ProviderRouter(List.of(providers), Duration.ofMinutes(5), clock,
                ru.akiev.gateway.router.RouterListener.NOOP, s, d -> {
                    sleeps.add(d);
                    clock.advance(d);
                });
        r.setRouting(new RoutingSettings(mode, null));
        return r;
    }

    /** ~4 токена входа + max_tokens. */
    static ChatRequest req(String model, int maxTokens) {
        return new ChatRequest(model, List.of(new Message("user", "hi")), false, null, null, maxTokens,
                null, null, null, null);
    }

    @Test
    void headroomModePicksProviderWithMostRoom() {
        Fake a = new Fake("a", 1, tpm(1000));
        Fake b = new Fake("b", 2, tpm(10_000));
        ProviderRouter r = router(settings(Duration.ZERO, false), RoutingMode.HEADROOM, a, b);
        assertThat(r.chat(req("x", 500)).id()).isEqualTo("b");
        // у b потрачено 50 из 10 000 — запас всё ещё больше, чем у a
        assertThat(r.chat(req("x", 500)).id()).isEqualTo("b");
        assertThat(a.calls).hasValue(0);
    }

    @Test
    void priorityModeSkipsProviderWithoutTokenRoom() {
        Fake a = new Fake("a", 1, tpm(1000));
        Fake b = new Fake("b", 2, null);
        a.usedTokens = 900;
        ProviderRouter r = router(settings(Duration.ZERO, false), RoutingMode.PRIORITY, a, b);
        assertThat(r.chat(req("x", 50)).id()).isEqualTo("a");
        // у a занято 900 из 1000 — запрос на ~54 токена ещё влезает, на ~504 — нет
        assertThat(r.chat(req("x", 500)).id()).isEqualTo("b");
        assertThat(a.state().budget().minuteTokens(clock.instant())).isEqualTo(900);
    }

    @Test
    void waitsUntilMinuteFreesThenSends() {
        Fake a = new Fake("a", 1, tpm(1000));
        a.usedTokens = 900;
        ProviderRouter r = router(settings(Duration.ofSeconds(90), false), RoutingMode.HEADROOM, a);
        r.chat(req("x", 50));
        clock.advance(Duration.ofSeconds(20));
        assertThat(r.chat(req("x", 500)).id()).isEqualTo("a");
        // ждали, пока первая резервация выйдет из минутного окна: 60 - 20 = 40 с
        assertThat(sleeps).containsExactly(Duration.ofSeconds(40));
        assertThat(a.calls).hasValue(2);
    }

    @Test
    void returns429AfterMaxWaitWithRetryAfter() {
        Fake a = new Fake("a", 1, new ProviderProperties.Limits(1, null));
        ProviderRouter r = router(settings(Duration.ofSeconds(10), false), RoutingMode.HEADROOM, a);
        r.chat(req("x", 10));
        assertThatThrownBy(() -> r.chat(req("x", 10)))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> assertThat(((RateLimitedException) e).retryAfter()).isEqualTo(Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty(); // освободится через 60 с > max-wait 10 с — ждать бессмысленно
        assertThat(a.calls).hasValue(1);
    }

    @Test
    void requestLargerThanEveryTpmIsRejectedImmediately() {
        Fake a = new Fake("a", 1, tpm(1000));
        Fake b = new Fake("b", 2, tpm(2000));
        ProviderRouter r = router(settings(Duration.ofSeconds(30), false), RoutingMode.HEADROOM, a, b);
        assertThatThrownBy(() -> r.chat(req("x", 5000))).isInstanceOf(RequestTooLargeException.class)
                .hasMessageContaining("tokens-per-minute");
        assertThat(a.calls.get() + b.calls.get()).isZero();
        assertThat(sleeps).isEmpty();
        // если хоть у одного TPM не ограничен — это не переполнение
        Fake c = new Fake("c", 3, null);
        ProviderRouter r2 = router(settings(Duration.ZERO, false), RoutingMode.HEADROOM, a, b, c);
        assertThat(r2.chat(req("x", 5000)).id()).isEqualTo("c");
    }

    @Test
    void provider429IsPausedAndFailoverHappens() {
        Fake a = new Fake("a", 1, null);
        Fake b = new Fake("b", 2, null);
        a.failWith = 429;
        ProviderRouter r = router(settings(Duration.ZERO, false), RoutingMode.PRIORITY, a, b);
        assertThat(r.chat(req("x", 10)).id()).isEqualTo("b");
        assertThat(a.state().pausedBy429At(clock.instant())).isEqualTo(clock.instant());
        assertThat(a.state().isAvailable(clock.instant())).isFalse();
        // неудачная попытка токены не тратит
        assertThat(a.state().budget().minuteTokens(clock.instant())).isZero();
        a.failWith = 0;
        assertThat(r.chat(req("x", 10)).id()).isEqualTo("b");
        // успешный ping (LimitDiscoveryService) снимает паузу — провайдер сразу снова первый
        a.state().include();
        assertThat(r.chat(req("x", 10)).id()).isEqualTo("a");
    }

    @Test
    void excludedProvidersOnlyGive503() {
        Fake a = new Fake("a", 1, null);
        a.state().exclude(clock.instant(), "нет ответа");
        ProviderRouter r = router(settings(Duration.ofSeconds(30), false), RoutingMode.HEADROOM, a);
        assertThatThrownBy(() -> r.chat(req("x", 10)))
                .isInstanceOf(ru.akiev.gateway.router.AllProvidersUnavailableException.class)
                .hasMessageContaining("excluded");
        assertThat(a.calls).hasValue(0);
    }

    @Test
    void onlyProvidersWithRequestedModelAreCandidates() {
        Fake a = new Fake("a", 1, null, "m-a");
        Fake b = new Fake("b", 2, null, "m-b", "special");
        ProviderRouter r = router(settings(Duration.ZERO, false), RoutingMode.PRIORITY, a, b);
        assertThat(r.chat(req("special", 10)).id()).isEqualTo("b");
        assertThat(r.chat(req("unknown-model", 10)).id()).isEqualTo("a");
    }

    @Test
    void pinModelPerRunKeepsProviderForRunId() {
        Fake a = new Fake("a", 1, null);
        Fake b = new Fake("b", 2, null);
        ProviderRouter r = router(settings(Duration.ZERO, true), RoutingMode.ROUND_ROBIN, a, b);
        try (var s = RequestTags.bind(new RequestTags("run-1", null, null, null))) {
            String first = r.chat(req("x", 10)).id();
            // round-robin сдвинул бы на другого — но run привязан
            assertThat(r.chat(req("x", 10)).id()).isEqualTo(first);
            assertThat(r.chat(req("x", 10)).id()).isEqualTo(first);
            // привязанный упал (5xx) — переходим на другого и перепривязываем
            Fake pinned = first.equals("a") ? a : b;
            pinned.failWith = 500;
            String second = r.chat(req("x", 10)).id();
            assertThat(second).isNotEqualTo(first);
            pinned.failWith = 0;
            pinned.state().resetCooldown();
            assertThat(r.chat(req("x", 10)).id()).isEqualTo(second);
        }
        // без x-run-id — обычный round-robin
        assertThat(List.of(r.chat(req("x", 10)).id(), r.chat(req("x", 10)).id())).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void pinnedProviderAtMinuteLimitMakesRunWaitInsteadOfSwitching() {
        Fake a = new Fake("a", 1, new ProviderProperties.Limits(1, null));
        Fake b = new Fake("b", 2, null);
        ProviderRouter r = router(settings(Duration.ofSeconds(90), true), RoutingMode.PRIORITY, a, b);
        try (var s = RequestTags.bind(new RequestTags("run-2", null, null, null))) {
            assertThat(r.chat(req("x", 10)).id()).isEqualTo("a");
            assertThat(r.chat(req("x", 10)).id()).isEqualTo("a");
            assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
        }
        assertThat(b.calls).hasValue(0);
    }
}
