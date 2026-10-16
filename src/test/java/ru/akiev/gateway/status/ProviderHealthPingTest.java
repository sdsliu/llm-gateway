package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderProperties;

/** Ping: 2xx = up при любой форме тела; ответ в формате OpenRouter (большой, с вложенными полями). */
class ProviderHealthPingTest {

    MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    ProviderHealthService service(String pingPath) {
        var p = new ProviderProperties("openrouter", server.url("/api/v1").toString(), "secret", "m", 1,
                Duration.ofSeconds(5), null, true, pingPath);
        return new ProviderHealthService(new GatewayProperties(List.of(p), Duration.ofMinutes(5)),
                WebClient.builder(), Clock.systemUTC(), Duration.ofSeconds(5), Duration.ofMinutes(10), false,
                DataSize.ofMegabytes(16));
    }

    /** Похоже на реальный GET https://openrouter.ai/api/v1/models: ~350 моделей, у каждой много полей. */
    static String openRouterModels(int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("""
                    {"id":"vendor/model-%d:free","canonical_slug":"vendor/model-%d","hugging_face_id":null,
                     "name":"Vendor: Model %d","created":1750000000,
                     "description":"%s",
                     "context_length":131072,
                     "architecture":{"modality":"text->text","input_modalities":["text"],"output_modalities":["text"],
                       "tokenizer":"Llama3","instruct_type":null},
                     "pricing":{"prompt":"0","completion":"0","request":"0","image":"0"},
                     "top_provider":{"context_length":131072,"max_completion_tokens":null,"is_moderated":false},
                     "per_request_limits":null,
                     "supported_parameters":["max_tokens","temperature","top_p","tools","tool_choice"],
                     "default_parameters":{}}
                    """.formatted(i, i, i, "x".repeat(800)));
        }
        return sb.append("]}").toString();
    }

    @Test
    void openRouterShapedLargeResponseIsUp() {
        String body = openRouterModels(350);
        assertThat(body.length()).isGreaterThan(256 * 1024); // больше дефолтного лимита WebClient
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        var r = service("/models").ping("openrouter");

        assertThat(r.state()).isEqualTo("up");
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.error()).isNull();
        assertThat(r.modelsCount()).isEqualTo(350);
    }

    @Test
    void unparsableTwoHundredIsStillUp() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"weird\":true}"));
        var r = service("/models").ping("openrouter");
        assertThat(r.state()).isEqualTo("up");
        assertThat(r.modelsCount()).isNull();
    }

    @Test
    void notFoundOnPingPathIsUpWithoutModels() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"not found\"}"));
        var s = service("/custom");
        var r = s.ping("openrouter");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/custom");
        assertThat(r.state()).isEqualTo("up");
        assertThat(r.httpStatus()).isEqualTo(404);
        assertThat(s.models("openrouter", false).error()).contains("не поддерживается");
    }

    @Test
    void unauthorizedIsDown() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));
        assertThat(service("/models").ping("openrouter").state()).isEqualTo("down");
    }

    @Test
    void parseIdsIsLenient() {
        assertThat(ProviderHealthService.parseIds("{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"},{\"x\":1}]}"))
                .containsExactly("a", "b");
        assertThat(ProviderHealthService.parseIds("[{\"id\":\"m1\"},\"m2\"]")).containsExactly("m1", "m2");
        assertThat(ProviderHealthService.parseIds("not json")).isNull();
    }
}
