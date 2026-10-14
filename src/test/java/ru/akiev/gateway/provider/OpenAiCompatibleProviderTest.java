package ru.akiev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
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
                "llama-3.3-70b-versatile", 1, timeout, null, true);
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
}
