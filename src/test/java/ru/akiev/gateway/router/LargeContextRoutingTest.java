package ru.akiev.gateway.router;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.config.ModelGroupProperties;
import ru.akiev.gateway.config.ModelGroups;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;

/**
 * Запрос ~30k токенов в группу large-context уходит участнику группы и не попадает на Groq.
 * Провайдеры — настоящий HTTP на WireMock, группа собрана из application.yml.
 */
class LargeContextRoutingTest {

    static final WireMockServer GROQ = new WireMockServer(wireMockConfig().dynamicPort().notifier(new ConsoleNotifier(false)));
    static final WireMockServer GONKA = new WireMockServer(wireMockConfig().dynamicPort().notifier(new ConsoleNotifier(false)));
    static final WireMockServer GEMINI = new WireMockServer(wireMockConfig().dynamicPort().notifier(new ConsoleNotifier(false)));

    static final ObjectMapper JSON = new ObjectMapper();
    static final int ABOUT_30K_CHARS = 120_000;
    static final int OVER_GROUP_CHARS = 400_000;

    static {
        GROQ.start();
        GONKA.start();
        GEMINI.start();
    }

    @AfterAll
    static void stop() {
        GROQ.stop();
        GONKA.stop();
        GEMINI.stop();
    }

    @BeforeEach
    void reset() {
        GROQ.resetAll();
        GONKA.resetAll();
        GEMINI.resetAll();
    }

    static String completion(String model) {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":30000,"completion_tokens":16,"total_tokens":30016}}""".formatted(model);
    }

    static void stub(WireMockServer server, String model) {
        server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(completion(model))));
    }

    static ProviderProperties provider(String name, WireMockServer server, int priority, String model,
            ProviderProperties.Limits limits, String probe) {
        return new ProviderProperties(name, server.baseUrl() + "/v1", name + "-test-key", model, priority,
                Duration.ofSeconds(5), limits, true, "/models", List.of(), Map.of(), false, List.of(), probe, "",
                null, "openai");
    }

    /** Группа из конфига, включены только groq (не участник), gonka и gemini. */
    static ModelGroups groups() throws Exception {
        var configured = ru.akiev.gateway.config.GonkaLargeContextConfigTest.bind(Map.of()).modelGroups();
        return ModelGroups.compile(configured, List.of("groq", "gonka", "gemini"));
    }

    static ProviderRouter router(RoutingMode mode) throws Exception {
        ModelGroups groups = groups();
        ModelGroupProperties group = groups.resolve("large-context").group();
        String gonkaModel = groups.modelFor(group, "gonka");
        String geminiModel = groups.modelFor(group, "gemini");
        stub(GROQ, "openai/gpt-oss-120b");
        stub(GONKA, gonkaModel);
        stub(GEMINI, geminiModel);
        var groq = new ru.akiev.gateway.provider.OpenAiCompatibleProvider(WebClient.builder(),
                provider("groq", GROQ, 1, "openai/gpt-oss-120b",
                        new ProviderProperties.Limits(30, 1000, 8000, null, null), "groq"));
        var gonka = new ru.akiev.gateway.provider.OpenAiCompatibleProvider(WebClient.builder(),
                provider("gonka", GONKA, 8, gonkaModel,
                        new ProviderProperties.Limits(6, null, 100_000, null, 1_000_000L), "gonka"));
        var gemini = new ru.akiev.gateway.provider.OpenAiCompatibleProvider(WebClient.builder(),
                provider("gemini", GEMINI, 9, geminiModel,
                        new ProviderProperties.Limits(5, null, 250_000, null, null), "models"));
        ProviderRouter r = new ProviderRouter(List.of(groq, gonka, gemini), Duration.ofMinutes(5), Clock.systemUTC(),
                RouterListener.NOOP, LimitsProperties.LEGACY, ProviderRouter.Sleeper.THREAD, groups);
        r.setRouting(new RoutingSettings(mode, null));
        return r;
    }

    /** ~4 символа на токен: 120_000 символов + одно сообщение + max_tokens 16 ≈ 30_020 токенов. */
    static ChatRequest request(int chars) {
        return new ChatRequest("large-context", List.of(new Message("user", "a".repeat(chars))), false,
                null, null, 16, null, null, null, null);
    }

    static String calledModel(WireMockServer server) throws Exception {
        assertThat(server.getAllServeEvents().size()).isEqualTo(1);
        JsonNode body = JSON.readTree(server.getAllServeEvents().get(0).getRequest().getBody());
        return body.get("model").asText();
    }

    @Test
    void about30kTokensInPriorityModeGoesToGonkaNotGroq() throws Exception {
        ModelGroups groups = groups();
        assertThat(groups.providerNames(groups.resolve("large-context").group()))
                .containsExactlyInAnyOrder("gonka", "gemini")
                .doesNotContain("groq");
        ProviderRouter r = router(RoutingMode.PRIORITY);
        assertThat(r.chat(request(ABOUT_30K_CHARS)).model()).isEqualTo(
                groups.modelFor(groups.resolve("large-context").group(), "gonka"));
        assertThat(GROQ.getAllServeEvents().size()).isZero();
        assertThat(GEMINI.getAllServeEvents().size()).isZero();
        assertThat(calledModel(GONKA)).isEqualTo(groups.modelFor(groups.resolve("large-context").group(), "gonka"));
    }

    @Test
    void about30kTokensInHeadroomModeDoesNotCallGroq() throws Exception {
        ProviderRouter r = router(RoutingMode.HEADROOM);
        String served = r.chat(request(ABOUT_30K_CHARS)).model();
        assertThat(GROQ.getAllServeEvents().size()).isZero();
        int hits = GONKA.getAllServeEvents().size() + GEMINI.getAllServeEvents().size();
        assertThat(hits).isEqualTo(1);
        String expected = GEMINI.getAllServeEvents().isEmpty()
                ? groups().modelFor(groups().resolve("large-context").group(), "gonka")
                : groups().modelFor(groups().resolve("large-context").group(), "gemini");
        assertThat(served).isEqualTo(expected);
        assertThat(served).isNotEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void requestAboveGroupTpmIsRejectedBeforeAnyProvider() throws Exception {
        ProviderRouter r = router(RoutingMode.PRIORITY);
        assertThatThrownBy(() -> r.chat(request(OVER_GROUP_CHARS)))
                .isInstanceOf(RequestTooLargeException.class)
                .hasMessageContaining("large-context")
                .hasMessageContaining("100000");
        assertThat(GROQ.getAllServeEvents().size()).isZero();
        assertThat(GONKA.getAllServeEvents().size()).isZero();
        assertThat(GEMINI.getAllServeEvents().size()).isZero();
    }
}
