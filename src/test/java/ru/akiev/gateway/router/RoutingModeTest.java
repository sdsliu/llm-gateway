package ru.akiev.gateway.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.provider.ProviderState;

/** Порядок перебора провайдеров в режимах PRIORITY и ROUND_ROBIN (без HTTP, фейковые провайдеры). */
class RoutingModeTest {

    /** Провайдер-заглушка: отвечает своим именем или падает с заданным статусом. */
    static final class Fake implements LlmProvider {
        final String name;
        final int priority;
        final ProviderState state = new ProviderState(null);
        final AtomicInteger calls = new AtomicInteger();
        volatile int failWith;
        /** Текст ошибки (например «No endpoints found» для 404 OpenRouter); null — «<name> failed». */
        volatile String failMessage;

        Fake(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        public String name() { return name; }
        public int priority() { return priority; }
        public ProviderState state() { return state; }
        public List<String> models() { return List.of("m-" + name); }

        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            if (failWith != 0) {
                throw new ProviderException(failWith, failMessage != null ? failMessage : name + " failed", null);
            }
            return new ChatResponse(name, "chat.completion", 1L, "m-" + name, List.of(), null);
        }

        public Flux<String> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            return failWith != 0
                    ? Flux.error(new ProviderException(failWith, failMessage != null ? failMessage : name + " failed", null))
                    : Flux.just("{\"id\":\"" + name + "\"}");
        }
    }

    final Clock clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    Fake a;
    Fake b;
    Fake c;
    ProviderRouter router;

    @BeforeEach
    void setUp() {
        a = new Fake("a", 1);
        b = new Fake("b", 2);
        c = new Fake("c", 3);
        router = new ProviderRouter(List.of(c, a, b), Duration.ofMinutes(5), clock);
    }

    ChatRequest req() {
        return new ChatRequest("x", List.of(new Message("user", "hi")), null, null, null, null, null, null, null, null);
    }

    String answer() {
        return router.chat(req()).id();
    }

    List<String> order() {
        return router.previewOrder().stream().map(LlmProvider::name).toList();
    }

    @Test
    void defaultIsPriorityOrder() {
        assertThat(router.routing()).isEqualTo(RoutingSettings.DEFAULT);
        assertThat(order()).containsExactly("a", "b", "c");
        assertThat(List.of(answer(), answer(), answer())).containsOnly("a");
    }

    @Test
    void primaryGoesFirstRestByPriority() {
        router.setRouting(new RoutingSettings(RoutingMode.PRIORITY, "c"));
        assertThat(order()).containsExactly("c", "a", "b");
        assertThat(answer()).isEqualTo("c");
    }

    @Test
    void primaryFailsOverToPriorityOrder() {
        router.setRouting(new RoutingSettings(RoutingMode.PRIORITY, "b"));
        b.failWith = 503;
        assertThat(answer()).isEqualTo("a");
        // b в cooldown — пропускаем его, идём сразу к a
        assertThat(answer()).isEqualTo("a");
        assertThat(b.calls).hasValue(1);
    }

    @Test
    void unknownPrimaryIsRejectedAndStateUnchanged() {
        assertThatThrownBy(() -> router.setRouting(new RoutingSettings(RoutingMode.PRIORITY, "nope")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope").hasMessageContaining("a, b, c");
        assertThat(router.routing()).isEqualTo(RoutingSettings.DEFAULT);
    }

    @Test
    void roundRobinRotates() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        assertThat(order()).containsExactly("a", "b", "c");
        assertThat(List.of(answer(), answer(), answer(), answer())).containsExactly("a", "b", "c", "a");
        assertThat(order()).containsExactly("b", "c", "a");
    }

    @Test
    void roundRobinIgnoresPrimary() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, "c"));
        assertThat(List.of(answer(), answer(), answer())).containsExactly("a", "b", "c");
        assertThat(router.routing().primary()).isEqualTo("c");
    }

    @Test
    void roundRobinSkipsUnavailable() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        b.state().cooldown(clock.instant().plusSeconds(60), "HTTP 429");
        assertThat(order()).containsExactly("a", "c", "b");
        assertThat(List.of(answer(), answer(), answer(), answer())).containsExactly("a", "c", "a", "c");
        assertThat(b.calls).hasValue(0);
    }

    @Test
    void roundRobinFailsOverToNextAvailable() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        assertThat(answer()).isEqualTo("a");
        b.failWith = 429; // очередь b → упал → следующий доступный c
        assertThat(answer()).isEqualTo("c");
        assertThat(b.state().isAvailable(clock.instant())).isFalse();
        // дальше крутимся между a и c
        assertThat(List.of(answer(), answer())).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void roundRobinAllUnavailableGives503() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        List.of(a, b, c).forEach(p -> p.state().cooldown(clock.instant().plusSeconds(60), "HTTP 429"));
        assertThatThrownBy(this::answer).isInstanceOf(AllProvidersUnavailableException.class)
                .hasMessageContaining("a: unavailable").hasMessageContaining("c: unavailable");
    }

    @Test
    void roundRobinAppliesToStreaming() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        List<String> used = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            StreamSession s = router.openStream(req());
            used.add(s.provider().name());
            s.close();
        }
        assertThat(used).containsExactly("a", "b", "c");
    }

    @Test
    void roundRobinCounterIsThreadSafe() throws Exception {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Runnable> tasks = Collections.nCopies(300, () -> hits.computeIfAbsent(answer(), k -> new AtomicInteger()).incrementAndGet());
        tasks.forEach(pool::submit);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(hits.keySet()).containsExactlyInAnyOrder("a", "b", "c");
        hits.values().forEach(v -> assertThat(v.get()).isEqualTo(100));
    }

    @Test
    void switchingBackToPriorityRestoresOrder() {
        router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        answer();
        router.setRouting(new RoutingSettings(RoutingMode.PRIORITY, null));
        assertThat(List.of(answer(), answer())).containsOnly("a");
    }

    @Test
    void modeParsingIsLenient() {
        assertThat(RoutingMode.parse("round-robin")).isEqualTo(RoutingMode.ROUND_ROBIN);
        assertThat(RoutingMode.parse(" Priority ")).isEqualTo(RoutingMode.PRIORITY);
        assertThat(RoutingMode.parse("random")).isNull();
        assertThat(RoutingMode.parse(null)).isNull();
    }

    @Test
    void modeChangeIsLoggedAtInfo() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderRouter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            router.setRouting(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(e.getFormattedMessage()).contains("PRIORITY").contains("ROUND_ROBIN");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }
}
