package ru.akiev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Message;

class OpenAiCompatibleProviderTest {

    static final String OPENAI_RESPONSE = """
            {"id":"chatcmpl-abc","object":"chat.completion","created":1700000000,
             "model":"llama-3.3-70b-versatile","system_fingerprint":"fp_x",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Привет!"},
                         "logprobs":null,"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
            """;

    MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    OpenAiCompatibleProvider provider(Duration timeout) {
        var props = new ProviderProperties("groq", server.url("/v1").toString(), "test-key",
                "llama-3.3-70b-versatile", 1, timeout, null, true, "/models", null);
        return new OpenAiCompatibleProvider(WebClient.builder(), props);
    }

    ChatRequest request() {
        return new ChatRequest("openai/coder", List.of(new Message("user", "hi")), true, 0.2,
                null, 100, null, null, null, null);
    }

    @Test
    void parsesOpenAiResponseAndSendsAuthAndModelOverride() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));

        ChatResponse resp = provider(Duration.ofSeconds(5)).chat(request());

        assertThat(resp.id()).isEqualTo("chatcmpl-abc");
        assertThat(resp.choices().get(0).message().content()).isEqualTo("Привет!");
        assertThat(resp.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(resp.usage().totalTokens()).isEqualTo(13);

        RecordedRequest rec = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(rec.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(rec.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(rec.getHeader("Content-Type")).startsWith("application/json");
        String body = rec.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"llama-3.3-70b-versatile\"")
                .contains("\"max_tokens\":100")
                .contains("\"stream\":false");
    }

    @Test
    void listedModelIsSentAsIs() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        var props = new ProviderProperties("openrouter", server.url("/v1").toString(), "test-key",
                "nvidia/nemotron-3-super-120b-a12b:free", 1, Duration.ofSeconds(5), null, true, "/models", null)
                .withExtraModels(List.of("openai/gpt-oss-120b:free"));
        var req = new ChatRequest("openai/gpt-oss-120b:free", List.of(new Message("user", "hi")), false,
                null, null, 10, null, null, null, null);

        new OpenAiCompatibleProvider(WebClient.builder(), props).chat(req);

        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"openai/gpt-oss-120b:free\"")
                .doesNotContain("nemotron");
    }

    @Test
    void httpErrorBecomesProviderException() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"rate\"}}"));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).chat(request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).status()).isEqualTo(429));
    }

    @Test
    void timeoutBecomes504() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(OPENAI_RESPONSE).setHeadersDelay(2, TimeUnit.SECONDS));
        assertThatThrownBy(() -> provider(Duration.ofMillis(300)).chat(request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).status()).isEqualTo(504));
    }

    OpenAiCompatibleProvider openRouter(List<String> fallbacks) {
        var props = new ProviderProperties("openrouter", server.url("/api/v1").toString(), "test-key",
                "nvidia/nemotron-3-super-120b-a12b:free", 3, Duration.ofSeconds(5), null, true, "/models", fallbacks);
        return new OpenAiCompatibleProvider(WebClient.builder(), props);
    }

    @Test
    void sendsOpenRouterFallbackModelsArrayInOrder() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        openRouter(List.of("google/gemma-4-31b-it:free", "openrouter/free")).chat(request());
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"nvidia/nemotron-3-super-120b-a12b:free\"")
                .contains("\"models\":[\"google/gemma-4-31b-it:free\",\"openrouter/free\"]")
                .contains("\"messages\":[").contains("\"stream\":false").doesNotContain("\"request\"");
    }

    @Test
    void sendsFallbackModelsInStreamRequest() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"x\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        openRouter(List.of("openrouter/free")).chatStream(request()).collectList().block(Duration.ofSeconds(5));
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"models\":[\"openrouter/free\"]").contains("\"stream\":true");
    }

    @Test
    void noModelsFieldWithoutFallbacks() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        provider(Duration.ofSeconds(5)).chat(request());
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()).doesNotContain("\"models\"");
    }

    OpenAiCompatibleProvider zai(Map<String, Object> extraBody, List<String> fallbacks) {
        var props = new ProviderProperties("zai", server.url("/api/paas/v4").toString(), "test-key",
                "glm-4.7-flash", 3, Duration.ofSeconds(5), null, true, "/models", fallbacks, extraBody);
        return new OpenAiCompatibleProvider(WebClient.builder(), props);
    }

    static final Map<String, Object> THINKING_OFF = Map.of("thinking", Map.of("type", "disabled"));

    @Test
    void sendsExtraBodyFieldsAtTopLevel() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        zai(THINKING_OFF, null).chat(request());
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"thinking\":{\"type\":\"disabled\"}")
                .contains("\"model\":\"glm-4.7-flash\"").contains("\"stream\":false")
                .doesNotContain("\"extra\"").doesNotContain("\"request\"").doesNotContain("\"models\"");
    }

    @Test
    void sendsExtraBodyInStreamRequest() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"x\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        zai(THINKING_OFF, null).chatStream(request()).collectList().block(Duration.ofSeconds(5));
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"thinking\":{\"type\":\"disabled\"}").contains("\"stream\":true");
    }

    @Test
    void extraBodyCannotOverrideGatewayFieldsAndCombinesWithFallbacks() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        zai(Map.of("model", "evil", "stream", true, "models", List.of("x"), "reasoning_effort", "low"),
                List.of("openrouter/free")).chat(request());
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"glm-4.7-flash\"").doesNotContain("evil")
                .contains("\"stream\":false").contains("\"models\":[\"openrouter/free\"]")
                .contains("\"reasoning_effort\":\"low\"");
    }

    @Test
    void exactGroupCallOmitsFallbacksAndDisablesOpenRouterSubstitution() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        var props = new ProviderProperties("openrouter", server.url("/api/v1").toString(), "test-key",
                "nvidia/nemotron-3-super-120b-a12b:free", 1, Duration.ofSeconds(5), null, true, "/models",
                List.of("google/gemma-4-31b-it:free", "poolside/laguna-s-2.1:free", "openrouter/free"),
                Map.of("route", "fallback", "provider", Map.of("order", List.of("DeepInfra"))))
                .withExtraModels(List.of("openai/gpt-oss-120b:free"));
        var req = new ChatRequest("openai/gpt-oss-120b:free", List.of(new Message("user", "hi")), false,
                null, null, 10, null, null, null, null);

        new OpenAiCompatibleProvider(WebClient.builder(), props).chat(req, true);

        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"openai/gpt-oss-120b:free\"")
                .contains("\"allow_fallbacks\":false")
                .contains("DeepInfra")
                .doesNotContain("\"models\"")
                .doesNotContain("gemma")
                .doesNotContain("laguna")
                .doesNotContain("openrouter/free")
                .doesNotContain("\"route\"")
                .doesNotContain("nemotron");
    }

    @Test
    void exactStreamCallOmitsOpenRouterFallbacks() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"x\",\"model\":\"openai/gpt-oss-120b:free\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        var req = new ChatRequest("openai/gpt-oss-120b:free", List.of(new Message("user", "hi")), true,
                null, null, 10, null, null, null, null);
        openRouter(List.of("google/gemma-4-31b-it:free", "openrouter/free"))
                .chatStream(req, true).collectList().block(Duration.ofSeconds(5));
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"openai/gpt-oss-120b:free\"")
                .contains("\"allow_fallbacks\":false")
                .contains("\"stream\":true")
                .doesNotContain("\"models\"")
                .doesNotContain("gemma");
    }

    @Test
    void exactCallOnOtherProviderDoesNotSendOpenRouterRouting() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        var props = new ProviderProperties("groq", server.url("/v1").toString(), "test-key",
                "openai/gpt-oss-120b", 1, Duration.ofSeconds(5), null, true, "/models",
                List.of("should-not-send"));
        var req = new ChatRequest("openai/gpt-oss-120b", List.of(new Message("user", "hi")), false,
                null, null, 10, null, null, null, null);

        new OpenAiCompatibleProvider(WebClient.builder(), props).chat(req, true);

        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"model\":\"openai/gpt-oss-120b\"")
                .doesNotContain("\"models\"")
                .doesNotContain("should-not-send")
                .doesNotContain("allow_fallbacks")
                .doesNotContain("\"provider\"");
    }

    @Test
    void openRouterHostDisablesFallbacksEvenIfNameDiffers() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OPENAI_RESPONSE));
        String base = server.url("/openrouter.ai/api/v1").toString();
        var props = new ProviderProperties("backup", base, "test-key",
                "openai/gpt-oss-120b:free", 1, Duration.ofSeconds(5), null, true, "/models",
                List.of("openrouter/free"));
        var req = new ChatRequest("openai/gpt-oss-120b:free", List.of(new Message("user", "hi")), false,
                null, null, 10, null, null, null, null);
        new OpenAiCompatibleProvider(WebClient.builder(), props).chat(req, true);
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"allow_fallbacks\":false")
                .contains("\"model\":\"openai/gpt-oss-120b:free\"")
                .doesNotContain("\"models\"");
    }

    @Test
    void nullTimeoutDefaultsTo30Seconds() {
        var props = new ProviderProperties("sambanova", server.url("/v1").toString(), "k",
                "gpt-oss-120b", 1, null, null, true, "/models", null);
        var p = new OpenAiCompatibleProvider(WebClient.builder(), props);
        assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void http402BecomesRetriableProviderException() {
        server.enqueue(new MockResponse().setResponseCode(402)
                .setBody("{\"error\":{\"code\":\"PAYMENT_METHOD_REQUIRED\"}}"));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).chat(request()))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).status()).isEqualTo(402))
                .satisfies(e -> assertThat(((ProviderException) e).isRetriable()).isTrue());
    }
}
