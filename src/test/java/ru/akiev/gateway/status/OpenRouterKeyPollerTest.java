package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderProperties;

class OpenRouterKeyPollerTest {

    GatewayProperties props(String url, String key) {
        return new GatewayProperties(List.of(new ProviderProperties("openrouter", url, key, "m", 3,
                Duration.ofSeconds(5), null, true, "/models")), Duration.ofMinutes(5));
    }

    @Test
    void pollsKeyEndpoint() throws Exception {
        try (MockWebServer s = new MockWebServer()) {
            s.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                    "{\"data\":{\"label\":\"sk-or\",\"usage\":0.5,\"limit\":10,\"limit_remaining\":9.5,\"is_free_tier\":true}}"));
            var poller = new OpenRouterKeyPoller(props(s.url("/api/v1").toString(), "or-key"),
                    WebClient.builder(), Clock.systemUTC(), "openrouter");
            poller.poll();
            var req = s.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/key");
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer or-key");
            assertThat(poller.data()).containsEntry("limit_remaining", 9.5).containsEntry("is_free_tier", true);
            assertThat(poller.fetchedAt()).isNotNull();
            assertThat(poller.error()).isNull();
        }
    }

    @Test
    void errorDoesNotThrow() throws Exception {
        try (MockWebServer s = new MockWebServer()) {
            s.enqueue(new MockResponse().setResponseCode(401));
            var poller = new OpenRouterKeyPoller(props(s.url("/api/v1").toString(), "bad"),
                    WebClient.builder(), Clock.systemUTC(), "openrouter");
            poller.poll();
            assertThat(poller.error()).isEqualTo("HTTP 401");
        }
    }

    @Test
    void skippedWithoutKey() {
        var poller = new OpenRouterKeyPoller(props("http://localhost:1", ""), WebClient.builder(),
                Clock.systemUTC(), "openrouter");
        assertThat(poller.enabled()).isFalse();
        poller.poll(); // ничего не делает и не падает
        assertThat(poller.error()).isNull();
    }
}
