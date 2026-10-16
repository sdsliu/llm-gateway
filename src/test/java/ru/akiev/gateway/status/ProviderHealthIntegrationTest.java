package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ping провайдеров (GET /models на MockWebServer), кэш моделей и поля квоты в /status.
 * alpha — рабочий, gamma — отвечает 401, beta — без ключа.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class ProviderHealthIntegrationTest {

    static final MockWebServer alpha = new MockWebServer();
    static final MockWebServer gamma = new MockWebServer();

    static final String MODELS = """
            {"object":"list","data":[{"id":"m-zeta","object":"model"},{"id":"m-alpha","object":"model"}]}
            """;
    static final String OK = """
            {"id":"x","object":"chat.completion","created":1,"model":"m-alpha",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
            """;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        alpha.start();
        gamma.start();
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> alpha.url("/v1").toString());
        r.add("gateway.providers[0].api-key", () -> "secret-alpha");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[0].limits.rpm", () -> "30");
        r.add("gateway.providers[0].limits.rpd", () -> "1000");
        r.add("gateway.providers[1].name", () -> "beta");
        r.add("gateway.providers[1].base-url", () -> "http://localhost:1/v1");
        r.add("gateway.providers[1].api-key", () -> "");
        r.add("gateway.providers[1].model", () -> "m-beta");
        r.add("gateway.providers[1].priority", () -> "2");
        r.add("gateway.providers[2].name", () -> "gamma");
        r.add("gateway.providers[2].base-url", () -> gamma.url("/v1").toString());
        r.add("gateway.providers[2].api-key", () -> "secret-gamma");
        r.add("gateway.providers[2].model", () -> "m-gamma");
        r.add("gateway.providers[2].priority", () -> "3");
    }

    @AfterAll
    static void stop() throws IOException {
        alpha.shutdown();
        gamma.shutdown();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void pingUpDownSkippedAndModels() throws Exception {
        alpha.enqueue(json(MODELS));
        mvc.perform(post("/status/providers/alpha/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("alpha"))
                .andExpect(jsonPath("$.state").value("up"))
                .andExpect(jsonPath("$.http_status").value(200))
                .andExpect(jsonPath("$.models_count").value(2))
                .andExpect(jsonPath("$.latency_ms").isNumber());
        RecordedRequest req = alpha.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/models");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer secret-alpha");

        gamma.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));
        alpha.enqueue(json(MODELS));
        String all = mvc.perform(post("/status/providers/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].state").value("up"))
                .andExpect(jsonPath("$[1].provider").value("beta"))
                .andExpect(jsonPath("$[1].state").value("skipped"))
                .andExpect(jsonPath("$[2].state").value("down"))
                .andExpect(jsonPath("$[2].http_status").value(401))
                .andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain("secret-");

        // Кэш: повторный запрос моделей не ходит к провайдеру.
        int before = alpha.getRequestCount();
        mvc.perform(get("/status/providers/alpha/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0]").value("m-alpha"))
                .andExpect(jsonPath("$.models[1]").value("m-zeta"));
        assertThat(alpha.getRequestCount()).isEqualTo(before);

        mvc.perform(get("/status/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway[0].id").value("m-alpha"))
                .andExpect(jsonPath("$.providers.alpha.models.length()").value(2))
                .andExpect(jsonPath("$.providers.beta.error").value("api-key не задан"))
                .andExpect(jsonPath("$.providers.gamma.error").value(org.hamcrest.Matchers.startsWith("HTTP 401")));

        mvc.perform(get("/status"))
                .andExpect(jsonPath("$.providers[0].last_ping.state").value("up"))
                .andExpect(jsonPath("$.providers[1].state").value("skipped"));

        mvc.perform(post("/status/providers/nope/ping")).andExpect(status().isNotFound());
    }

    @Test
    void quotaFromHeadersIsExposedAndUnknownStaysNull() throws Exception {
        alpha.enqueue(json(OK)
                .setHeader("x-ratelimit-remaining-requests", "998")
                .setHeader("x-ratelimit-remaining-tokens", "7000")
                .setHeader("x-ratelimit-reset-requests", "2m")
                .setHeader("x-ratelimit-reset-tokens", "6s"));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/status"))
                .andExpect(jsonPath("$.providers[0].remaining_requests").value(998))
                .andExpect(jsonPath("$.providers[0].remaining_tokens").value(7000))
                .andExpect(jsonPath("$.providers[0].reset_requests_at").isString())
                .andExpect(jsonPath("$.providers[0].reset_tokens_at").isString())
                .andExpect(jsonPath("$.providers[0].limits_seen_at").isString())
                .andExpect(jsonPath("$.providers[0].rpm_limit").value(30))
                .andExpect(jsonPath("$.providers[0].rpd_limit").value(1000))
                .andExpect(jsonPath("$.providers[0].rpd_used").isNumber())
                // gamma ни разу не присылал x-ratelimit-* — полей нет, UI покажет «неизвестно».
                .andExpect(jsonPath("$.providers[2].name").value("gamma"))
                .andExpect(jsonPath("$.providers[2].remaining_requests").doesNotExist())
                .andExpect(jsonPath("$.providers[2].limits_seen_at").doesNotExist());
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
