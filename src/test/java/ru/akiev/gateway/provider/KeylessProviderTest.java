package ru.akiev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderConfig;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.status.ProviderHealthService;
import ru.akiev.gateway.usage.UsageService;

/**
 * Keyless-провайдеры (api-key-optional: true, например Kilo Gateway, OVHcloud AI Endpoints):
 * без ключа не пропускаются и ходят БЕЗ заголовка Authorization; с ключом — обычный Bearer.
 */
class KeylessProviderTest {

    static final String OK = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
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

    ProviderProperties props(String name, String key, boolean optional) {
        return new ProviderProperties(name, server.url("/api/v1").toString(), key, "kilo-auto/free", 3,
                Duration.ofSeconds(5), null, true, "/models", null, null, optional);
    }

    ChatRequest request() {
        return new ChatRequest("any", List.of(new Message("user", "ping")), false, null,
                null, 10, null, null, null, null);
    }

    @Test
    void enabledSemantics() {
        assertThat(props("a", "", false).enabled()).isFalse();
        assertThat(props("a", null, false).enabled()).isFalse();
        assertThat(props("a", "  ", true).enabled()).isTrue();
        assertThat(props("a", "  ", true).hasApiKey()).isFalse();
        assertThat(props("a", "k", false).enabled()).isTrue();
        // старые конструкторы: ключ обязателен
        var legacy = new ProviderProperties("a", "http://x/v1", "", "m", 1, Duration.ofSeconds(5), null, true,
                "/models", null);
        assertThat(legacy.apiKeyOptional()).isFalse();
        assertThat(legacy.enabled()).isFalse();
    }

    @Test
    void keylessSendsNoAuthorizationHeader() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK));

        var resp = new OpenAiCompatibleProvider(WebClient.builder(), props("kilo", "", true)).chat(request());

        assertThat(resp.choices().get(0).message().content()).isEqualTo("pong");
        RecordedRequest rec = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(rec.getPath()).isEqualTo("/api/v1/chat/completions");
        assertThat(rec.getHeader("Authorization")).isNull();
        assertThat(rec.getBody().readUtf8()).contains("\"model\":\"kilo-auto/free\"");
    }

    @Test
    void optionalKeyIsSentWhenPresent() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK));

        new OpenAiCompatibleProvider(WebClient.builder(), props("kilo", "secret", true)).chat(request());

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer secret");
    }

    @Test
    void routerKeepsKeylessAndSkipsKeyRequiredWithoutKey() {
        var gp = new GatewayProperties(List.of(props("kilo", "", true), props("zai", "", false),
                props("groq", "k", false)), Duration.ofMinutes(5));

        ProviderRouter router = new ProviderConfig().providerRouter(WebClient.builder(), gp, Clock.systemUTC(),
                mock(UsageService.class), null);

        assertThat(router.names()).containsExactlyInAnyOrder("kilo", "groq");
    }

    @Test
    void healthPingOfKeylessProviderWithoutAuthorization() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"id\":\"kilo-auto/free\"}]}"));
        var health = new ProviderHealthService(
                new GatewayProperties(List.of(props("kilo", "", true)), Duration.ofMinutes(5)),
                WebClient.builder(), Clock.systemUTC(), Duration.ofSeconds(5), Duration.ofMinutes(10), false,
                DataSize.ofMegabytes(16));

        var r = health.ping("kilo");

        assertThat(r.state()).isEqualTo("up");
        RecordedRequest rec = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(rec.getPath()).isEqualTo("/api/v1/models");
        assertThat(rec.getHeader("Authorization")).isNull();
    }
}
