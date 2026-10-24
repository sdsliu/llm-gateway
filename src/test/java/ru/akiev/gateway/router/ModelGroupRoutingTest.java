package ru.akiev.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.config.ModelGroupProperties;
import ru.akiev.gateway.config.ModelGroupProperties.Member;
import ru.akiev.gateway.config.ModelGroups;
import ru.akiev.gateway.config.ProviderConfig;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.config.RoutingProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.usage.UsageService;

/** Группы моделей: алиасы, разведение параллельного всплеска, failover 404 и 429. */
class ModelGroupRoutingTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);

    static final class Fake implements LlmProvider {
        final String name;
        final int priority;
        final ProviderState state = new ProviderState(null);
        final List<String> models;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> seenModels = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile int failWith;
        volatile String failMessage;
        volatile Duration retryAfter;
        volatile Integer maxConcurrency;
        volatile CountDownLatch arrived;
        volatile CountDownLatch release;

        Fake(String name, int priority, String... models) {
            this.name = name;
            this.priority = priority;
            this.models = models.length == 0 ? List.of("m-" + name) : List.of(models);
        }

        public String name() { return name; }
        public int priority() { return priority; }
        public ProviderState state() { return state; }
        public List<String> models() { return models; }
        public Integer maxConcurrency() { return maxConcurrency; }

        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            seenModels.add(request.model());
            awaitTurn();
            if (failWith != 0) {
                throw new ProviderException(failWith, failMessage != null ? failMessage : name + " failed", retryAfter, null);
            }
            return new ChatResponse(name, "chat.completion", 1L, request.model(), List.of(), null);
        }

        public Flux<String> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            seenModels.add(request.model());
            if (failWith != 0) {
                return Flux.error(new ProviderException(failWith,
                        failMessage != null ? failMessage : name + " failed", retryAfter, null));
            }
            return Flux.just("{\"id\":\"" + name + "\"}");
        }

        private void awaitTurn() {
            if (arrived != null) {
                arrived.countDown();
            }
            if (release != null) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new ProviderException(504, name + " test release timeout", null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException(504, "interrupted", e);
                }
            }
        }
    }

    static ChatRequest request(String model) {
        return new ChatRequest(model, List.of(new Message("user", "hi")), false, null, null, 16,
                null, null, null, null);
    }

    static ModelGroupProperties gptOss() {
        return new ModelGroupProperties("gpt-oss-120b", List.of("openai/gpt-oss-120b"), List.of(
                new Member("groq", "openai/gpt-oss-120b"),
                new Member("ovh", "gpt-oss-120b"),
                new Member("sambanova", "gpt-oss-120b"),
                new Member("openrouter", "openai/gpt-oss-120b:free")));
    }

    static ModelGroups groups(String... enabled) {
        return ModelGroups.compile(List.of(gptOss()), List.of(enabled));
    }

    ProviderRouter router(ModelGroups groups, RoutingMode mode, Fake... providers) {
        ProviderRouter r = new ProviderRouter(List.of(providers), Duration.ofMinutes(5), clock,
                RouterListener.NOOP, LimitsProperties.LEGACY, ProviderRouter.Sleeper.THREAD, groups);
        r.setRouting(new RoutingSettings(mode, null));
        return r;
    }

    @Test
    void aliasResolvesToGroupAndRewritesProviderModel() {
        ModelGroups groups = groups("groq", "ovh", "sambanova", "openrouter");
        assertThat(groups.resolve("gpt-oss-120b").kind()).isEqualTo(ModelGroups.Kind.GROUP);
        assertThat(groups.resolve(" openai/gpt-oss-120b ").group().name()).isEqualTo("gpt-oss-120b");
        assertThat(groups.resolve("auto").kind()).isEqualTo(ModelGroups.Kind.AUTO);
        assertThat(groups.resolve("unknown-model").kind()).isEqualTo(ModelGroups.Kind.EXACT);
        assertThat(groups.resolve(null).kind()).isEqualTo(ModelGroups.Kind.EXACT);
        assertThat(groups.modelFor(groups.resolve("gpt-oss-120b").group(), "groq")).isEqualTo("openai/gpt-oss-120b");
        assertThat(groups.modelFor(groups.resolve("gpt-oss-120b").group(), "ovh")).isEqualTo("gpt-oss-120b");
        assertThat(groups.modelFor(groups.resolve("gpt-oss-120b").group(), "openrouter"))
                .isEqualTo("openai/gpt-oss-120b:free");
        assertThat(groups.advertised()).contains("gpt-oss-120b", "openai/gpt-oss-120b");

        Fake groq = new Fake("groq", 1, "openai/gpt-oss-120b");
        Fake ovh = new Fake("ovh", 2, "gpt-oss-120b");
        groq.failWith = 404;
        groq.failMessage = "model not found";
        ProviderRouter r = router(groups, RoutingMode.PRIORITY, groq, ovh);
        assertThat(r.chat(request("openai/gpt-oss-120b")).id()).isEqualTo("ovh");
        assertThat(groq.seenModels).containsExactly("openai/gpt-oss-120b");
        assertThat(ovh.seenModels).containsExactly("gpt-oss-120b");
    }

    @Test
    void duplicateAliasFailsStartup() {
        ModelGroupProperties other = new ModelGroupProperties("other", List.of("openai/gpt-oss-120b"),
                List.of(new Member("ovh", "gpt-oss-120b")));
        assertThatThrownBy(() -> ModelGroups.compile(List.of(gptOss(), other), List.of("groq", "ovh")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai/gpt-oss-120b");
    }

    @Test
    void disabledMemberIsDropped() {
        ModelGroups groups = groups("ovh");
        assertThat(groups.providerNames(groups.resolve("gpt-oss-120b").group())).containsExactly("ovh");
    }

    @Test
    void headroomSpreadsParallelBurstAcrossProviders() throws Exception {
        Fake groq = new Fake("groq", 1);
        Fake ovh = new Fake("ovh", 2);
        Fake samba = new Fake("sambanova", 3);
        Fake routerOr = new Fake("openrouter", 4);
        ProviderRouter r = router(groups("groq", "ovh", "sambanova", "openrouter"), RoutingMode.HEADROOM,
                groq, ovh, samba, routerOr);
        int n = 8;
        CountDownLatch arrived = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        for (Fake f : List.of(groq, ovh, samba, routerOr)) {
            f.arrived = arrived;
            f.release = release;
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> r.chat(request("gpt-oss-120b")).id()));
            }
            assertThat(arrived.await(5, TimeUnit.SECONDS)).as("все 8 запросов вошли в провайдера").isTrue();
            release.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<String> f : futures) {
                ids.add(f.get(5, TimeUnit.SECONDS));
            }
            assertThat(ids.size()).isGreaterThanOrEqualTo(3);
            Map<String, String> expected = Map.of(
                    "groq", "openai/gpt-oss-120b",
                    "ovh", "gpt-oss-120b",
                    "sambanova", "gpt-oss-120b",
                    "openrouter", "openai/gpt-oss-120b:free");
            for (Fake f : List.of(groq, ovh, samba, routerOr)) {
                if (f.calls.get() > 0) {
                    assertThat(f.seenModels).containsOnly(expected.get(f.name));
                }
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void maxConcurrencyReturns429WhileSlotsAreBusy() throws Exception {
        Fake a = new Fake("a", 1);
        Fake b = new Fake("b", 2);
        a.maxConcurrency = 1;
        b.maxConcurrency = 1;
        ProviderRouter r = router(ModelGroups.EMPTY, RoutingMode.HEADROOM, a, b);
        CountDownLatch arrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        a.arrived = arrived;
        b.arrived = arrived;
        a.release = release;
        b.release = release;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> r.chat(request("auto")));
            Future<?> f2 = pool.submit(() -> r.chat(request("auto")));
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> r.chat(request("auto")))
                    .isInstanceOf(RateLimitedException.class)
                    .hasMessageContaining("max concurrency");
            release.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            assertThat(List.of(a.seenModels.get(0), b.seenModels.get(0))).containsExactlyInAnyOrder("m-a", "m-b");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void plain404OutsideGroupIsNotFailover() {
        Fake groq = new Fake("groq", 1, "openai/gpt-oss-120b");
        Fake ovh = new Fake("ovh", 2, "gpt-oss-120b");
        groq.failWith = 404;
        groq.failMessage = "model not found";
        ProviderRouter r = router(ModelGroups.EMPTY, RoutingMode.PRIORITY, groq, ovh);
        assertThatThrownBy(() -> r.chat(request("openai/gpt-oss-120b")))
                .isInstanceOf(ProviderException.class);
        assertThat(ovh.calls).hasValue(0);
        assertThat(groq.state().isAvailable(clock.instant())).isTrue();
    }

    @Test
    void group404FailsOverAndCoolsDown() {
        Fake groq = new Fake("groq", 1);
        Fake ovh = new Fake("ovh", 2);
        groq.failWith = 404;
        groq.failMessage = "{\"error\":\"Not Found\"}";
        ProviderRouter r = router(groups("groq", "ovh"), RoutingMode.PRIORITY, groq, ovh);
        assertThat(r.chat(request("gpt-oss-120b")).id()).isEqualTo("ovh");
        assertThat(groq.calls).hasValue(1);
        assertThat(ovh.calls).hasValue(1);
        assertThat(groq.state().isAvailable(clock.instant())).isFalse();
        assertThat(groq.state().cooldownUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(groq.state().cooldownReason()).isEqualTo("HTTP 404");
        // и в стриме
        groq.state().resetCooldown();
        groq.calls.set(0);
        ovh.calls.set(0);
        StreamSession session = r.openStream(request("gpt-oss-120b"));
        try {
            assertThat(session.provider().name()).isEqualTo("ovh");
        } finally {
            session.close();
        }
    }

    @Test
    void group402FailsOver() {
        Fake samba = new Fake("sambanova", 1);
        Fake ovh = new Fake("ovh", 2);
        samba.failWith = 402;
        samba.failMessage = "PAYMENT_METHOD_REQUIRED";
        ProviderRouter r = router(groups("sambanova", "ovh"), RoutingMode.PRIORITY, samba, ovh);
        assertThat(r.chat(request("openai/gpt-oss-120b")).id()).isEqualTo("ovh");
        assertThat(samba.state().cooldownReason()).isEqualTo("HTTP 402");
        assertThat(samba.state().isAvailable(clock.instant())).isFalse();
    }

    @Test
    void group429RespectsRetryAfterAndFailsOverBeforeReturning429() {
        Fake groq = new Fake("groq", 1);
        Fake ovh = new Fake("ovh", 2);
        groq.failWith = 429;
        groq.retryAfter = Duration.ofSeconds(15);
        ProviderRouter r = router(groups("groq", "ovh"), RoutingMode.PRIORITY, groq, ovh);
        assertThat(r.chat(request("gpt-oss-120b")).id()).isEqualTo("ovh");
        assertThat(groq.state().cooldownUntil()).isEqualTo(clock.instant().plusSeconds(15));
        assertThat(groq.state().pausedBy429At(clock.instant())).isEqualTo(clock.instant());
        assertThat(ovh.calls).hasValue(1);

        groq.calls.set(0);
        ovh.calls.set(0);
        ovh.failWith = 429;
        ovh.retryAfter = Duration.ofSeconds(40);
        assertThatThrownBy(() -> r.chat(request("gpt-oss-120b")))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> assertThat(((RateLimitedException) e).retryAfter()).isEqualTo(Duration.ofSeconds(40)));
        assertThat(groq.calls).hasValue(0); // ещё в паузе от первого 429 — к нему не ходим
        assertThat(ovh.calls).hasValue(1);
    }

    @Test
    void bothProviders429Returns429WithSoonestRetryAfter() {
        Fake groq = new Fake("groq", 1);
        Fake ovh = new Fake("ovh", 2);
        groq.failWith = 429;
        groq.retryAfter = Duration.ofSeconds(40);
        ovh.failWith = 429;
        ovh.retryAfter = Duration.ofSeconds(12);
        ProviderRouter r = router(groups("groq", "ovh"), RoutingMode.PRIORITY, groq, ovh);
        assertThatThrownBy(() -> r.chat(request("gpt-oss-120b")))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("groq")
                .hasMessageContaining("ovh")
                .satisfies(e -> assertThat(((RateLimitedException) e).retryAfter()).isEqualTo(Duration.ofSeconds(12)));
        assertThat(groq.calls).hasValue(1);
        assertThat(ovh.calls).hasValue(1);
    }

    @Test
    void autoUsesEachProvidersOwnModel() {
        Fake a = new Fake("a", 1, "model-a");
        Fake b = new Fake("b", 2, "model-b");
        ProviderRouter r = router(ModelGroups.EMPTY, RoutingMode.PRIORITY, a, b);
        assertThat(r.chat(request("auto")).id()).isEqualTo("a");
        assertThat(a.seenModels).containsExactly("model-a");
        assertThat(b.calls).hasValue(0);
    }

    static String ok(String text) {
        return """
                {"id":"x","object":"chat.completion","created":1,"model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.formatted(text);
    }

    @Test
    void httpGroupRewritesModelAndFailsOverOn404() throws Exception {
        MockWebServer groq = new MockWebServer();
        MockWebServer ovh = new MockWebServer();
        groq.start();
        ovh.start();
        try {
            groq.enqueue(new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":{\"message\":\"Not Found\",\"code\":404}}"));
            ovh.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(ok("from-ovh")));
            ProviderProperties groqProps = new ProviderProperties("groq", groq.url("/v1").toString(), "k-groq",
                    "openai/gpt-oss-120b", 1, Duration.ofSeconds(5), null, true, "/models", null);
            ProviderProperties ovhProps = new ProviderProperties("ovh", ovh.url("/v1").toString(), "k-ovh",
                    "gpt-oss-120b", 2, Duration.ofSeconds(5), null, true, "/models", null);
            ProviderProperties openrouter = new ProviderProperties("openrouter", "https://openrouter.ai/api/v1", "k-or",
                    "nvidia/nemotron-3-super-120b-a12b:free", 6, Duration.ofSeconds(5), null, true, "/models", null);
            var gp = new ru.akiev.gateway.config.GatewayProperties(
                    List.of(groqProps, ovhProps, openrouter), Duration.ofMinutes(5), List.of(gptOss()));
            ProviderRouter r = new ProviderConfig().providerRouter(WebClient.builder(), gp, clock,
                    org.mockito.Mockito.mock(UsageService.class),
                    new RoutingProperties(RoutingMode.PRIORITY, null), LimitsProperties.LEGACY);
            assertThat(r.names()).contains("groq", "ovh", "openrouter");
            assertThat(r.providers().stream().filter(p -> p.name().equals("openrouter")).findFirst().orElseThrow().models())
                    .contains("openai/gpt-oss-120b:free", "nvidia/nemotron-3-super-120b-a12b:free");

            assertThat(r.chat(request("gpt-oss-120b")).choices().get(0).message().content()).isEqualTo("from-ovh");
            String groqBody = groq.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
            String ovhBody = ovh.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
            assertThat(groqBody).contains("\"model\":\"openai/gpt-oss-120b\"");
            assertThat(ovhBody).contains("\"model\":\"gpt-oss-120b\"").doesNotContain("openai/gpt-oss-120b");
            LlmProvider groqProvider = r.providers().stream().filter(p -> p.name().equals("groq")).findFirst().orElseThrow();
            assertThat(groqProvider.state().cooldownReason()).isEqualTo("HTTP 404");
        } finally {
            groq.shutdown();
            ovh.shutdown();
        }
    }
}
