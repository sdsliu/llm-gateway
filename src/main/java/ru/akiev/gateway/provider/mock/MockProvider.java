package ru.akiev.gateway.provider.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Choice;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.provider.TerminalProviderException;
import ru.akiev.gateway.usage.RequestTags;
import ru.akiev.gateway.usage.UsageService;

/**
 * Провайдер без сети: ответ берётся из {@link MockScenarioCatalog}.
 * Сценарий — заголовок {@code x-mock-scenario}, иначе кусок {@code x-run-id}, иначе сценарий по умолчанию.
 * Успешный ответ (и стрим) содержит {@code usage}, чтобы учёт по {@code run_id} шёл как у обычного провайдера.
 * Ошибки 429/503/таймаут — {@link TerminalProviderException}: клиент видит этот статус, а не failover.
 */
public class MockProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final ProviderProperties props;
    private final Clock clock;
    private final MockScenarioCatalog catalog;
    private final UsageService usage;
    private final ProviderState state;
    private final Duration timeout;

    public MockProvider(ProviderProperties props, Clock clock, MockScenarioCatalog catalog, UsageService usage) {
        this.props = props;
        this.clock = clock;
        this.catalog = catalog;
        this.usage = usage;
        this.state = new ProviderState(props.limits());
        this.timeout = props.timeout() != null ? props.timeout() : Duration.ofSeconds(2);
    }

    @Override
    public String name() {
        return props.name();
    }

    @Override
    public int priority() {
        return props.priority();
    }

    @Override
    public List<String> models() {
        return props.allModels();
    }

    @Override
    public ProviderState state() {
        return state;
    }

    @Override
    public Integer maxConcurrency() {
        return props.maxConcurrency();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return chat(request, false);
    }

    @Override
    public ChatResponse chat(ChatRequest request, boolean exactModel) {
        MockScenario scenario = current();
        return switch (scenario.mode()) {
            case COMPLETION -> completion(request, scenario, scenario.content());
            case STREAM_CUT -> completion(request, scenario, scenario.firstChunk());
            case ERROR -> {
                fail(scenario);
                yield null;
            }
            case TIMEOUT -> {
                timeout();
                yield null;
            }
            case RAW -> {
                // Роутер эту ошибку не ловит: тело уходит клиенту как есть, usage пишем сами.
                long start = System.nanoTime();
                usage.onSuccess(this, completion(request, scenario, ""), elapsed(start));
                throw new RawMockBody(scenario.status(), scenario.body());
            }
        };
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        return chatStream(request, false);
    }

    @Override
    public Flux<String> chatStream(ChatRequest request, boolean exactModel) {
        MockScenario scenario = current();
        String model = responseModel(request);
        return switch (scenario.mode()) {
            case COMPLETION -> Flux.fromIterable(chunks(scenario, model, scenario.content()));
            case STREAM_CUT -> streamCut(scenario, model);
            case ERROR -> Flux.error(error(scenario));
            case TIMEOUT -> {
                timeout();
                yield Flux.empty();
            }
            case RAW -> Flux.just(scenario.body(), usageChunk(scenario, model));
        };
    }

    /**
     * Первый чанк синхронно, ошибка — чуть позже, отдельным сигналом.
     * Иначе {@code Flux.toStream} видит уже завершившийся с ошибкой поток и выбрасывает её
     * до того, как отдаст лежащий в очереди чанк: клиент не увидел бы обрыв «после первого чанка».
     */
    private Flux<String> streamCut(MockScenario scenario, String model) {
        String first = contentChunk(scenario, model, scenario.firstChunk(), true, false);
        AtomicBoolean started = new AtomicBoolean();
        return Flux.create(sink -> sink.onRequest(n -> {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            sink.next(first);
            Thread.ofVirtual().name("mock-stream-cut").start(() -> {
                try {
                    Thread.sleep(20);
                    if (!sink.isCancelled()) {
                        sink.error(new ProviderException(502,
                                "mock scenario stream-cut: stream closed after the first chunk", null));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException ignored) {
                    // Подписчик уже ушёл — обрыв никому не доставляем.
                }
            });
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    private MockScenario current() {
        String runId = RequestTags.current().runId();
        if (runId == null) {
            runId = MockScenarioFilter.runIdHeader();
        }
        MockScenario scenario = catalog.resolve(MockScenarioFilter.scenarioHeader(), runId);
        log.info("Mock {}: сценарий {} (run_id={})", name(), scenario.id(), runId);
        return scenario;
    }

    private void fail(MockScenario scenario) {
        throw error(scenario);
    }

    private RuntimeException error(MockScenario scenario) {
        // 413 — обычный путь шлюза: «запрос слишком большой», без cooldown.
        if (scenario.status() == 413) {
            return new ProviderException(413, scenario.message(), null);
        }
        return new TerminalProviderException(scenario.status(), scenario.message(), scenario.retryAfter());
    }

    private void timeout() {
        try {
            Thread.sleep(Math.max(1, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new TerminalProviderException(504, "Provider " + name() + " timed out after " + timeout, null);
    }

    private ChatResponse completion(ChatRequest request, MockScenario scenario, String content) {
        return new ChatResponse(id(scenario), "chat.completion", created(), responseModel(request),
                List.of(new Choice(0, new Message("assistant", content), "stop")), scenario.usage());
    }

    private List<String> chunks(MockScenario scenario, String model, String content) {
        List<String> parts = splitLines(content);
        List<String> out = new ArrayList<>();
        if (parts.isEmpty()) {
            out.add(contentChunk(scenario, model, "", true, true));
        } else {
            for (int i = 0; i < parts.size(); i++) {
                out.add(contentChunk(scenario, model, parts.get(i), i == 0, i == parts.size() - 1));
            }
        }
        out.add(usageChunk(scenario, model));
        return out;
    }

    private String contentChunk(MockScenario scenario, String model, String content, boolean role, boolean finish) {
        ObjectNode root = base(scenario, model);
        ObjectNode choice = JSON.createObjectNode();
        choice.put("index", 0);
        ObjectNode delta = JSON.createObjectNode();
        if (role) {
            delta.put("role", "assistant");
        }
        delta.put("content", content);
        choice.set("delta", delta);
        if (finish) {
            choice.put("finish_reason", "stop");
        }
        root.putArray("choices").add(choice);
        return write(root);
    }

    private String usageChunk(MockScenario scenario, String model) {
        ObjectNode root = base(scenario, model);
        root.putArray("choices");
        Usage u = scenario.usage();
        ObjectNode usageNode = JSON.createObjectNode();
        usageNode.put("prompt_tokens", u.promptTokens() == null ? 0 : u.promptTokens());
        usageNode.put("completion_tokens", u.completionTokens() == null ? 0 : u.completionTokens());
        usageNode.put("total_tokens", u.totalTokens());
        root.set("usage", usageNode);
        return write(root);
    }

    private ObjectNode base(MockScenario scenario, String model) {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", id(scenario));
        root.put("object", "chat.completion.chunk");
        root.put("created", created());
        root.put("model", model);
        return root;
    }

    private static String write(ObjectNode root) {
        try {
            return JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ProviderException(500, "mock scenario failed to encode a chunk", e);
        }
    }

    /** Режем по строкам, чтобы маркер SEARCH/REPLACE не разрывался между чанками. */
    static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String[] lines = content.split("\\R", -1);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = i < lines.length - 1 ? lines[i] + "\n" : lines[i];
            if (!line.isEmpty()) {
                parts.add(line);
            }
        }
        return parts;
    }

    private String responseModel(ChatRequest request) {
        if (request != null && request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return props.model();
    }

    private static String id(MockScenario scenario) {
        return "chatcmpl-mock-" + scenario.id();
    }

    private long created() {
        return clock.instant().getEpochSecond();
    }

    private static long elapsed(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
