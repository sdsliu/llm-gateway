package ru.akiev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

/** base-url /api/v1 (со слэшем и без) + "/chat/completions" даёт ровно /api/v1/chat/completions. */
class OpenRouterUrlJoinTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/v1/"})
    void joinsChatPath(String base) throws Exception {
        try (var s = new MockWebServer()) {
            s.enqueue(new MockResponse().setBody("{}"));
            s.start();
            WebClient.builder().baseUrl(s.url(base).toString()).build()
                    .post().uri("/chat/completions").retrieve().toBodilessEntity().block();
            assertThat(s.takeRequest().getPath()).isEqualTo("/api/v1/chat/completions");
        }
    }
}
