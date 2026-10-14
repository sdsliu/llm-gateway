package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Полный контекст: H2 + Flyway + роутер с двумя провайдерами на MockWebServer.
 * Проверяем запись usage_record, GET /status и SSE-поток /status/stream.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatusIntegrationTest {

    static final MockWebServer upstream = new MockWebServer();

    static final String OK = """
            {"id":"x","object":"chat.completion","created":1,"model":"m-alpha",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
            """;
    static final String BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        upstream.start();
        String url = upstream.url("/v1").toString();
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> url);
        r.add("gateway.providers[0].api-key", () -> "k");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[0].limits.rpm", () -> "100");
        r.add("gateway.providers[1].name", () -> "beta");
        r.add("gateway.providers[1].base-url", () -> "http://localhost:1/v1");
        r.add("gateway.providers[1].api-key", () -> "");
        r.add("gateway.providers[1].model", () -> "m-beta");
        r.add("gateway.providers[1].priority", () -> "2");
    }

    @AfterAll
    static void stop() throws IOException {
        upstream.shutdown();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    UsageRecordRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void successAndFailureArePersistedAndShownInStatus() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK)
                .setHeader("x-ratelimit-remaining-requests", "999"));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        upstream.enqueue(new MockResponse().setResponseCode(500));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());

        var records = repository.findAll();
        assertThat(records).hasSize(2);
        UsageRecord ok = records.stream().filter(UsageRecord::isSuccess).findFirst().orElseThrow();
        assertThat(ok.getProvider()).isEqualTo("alpha");
        assertThat(ok.getModel()).isEqualTo("m-alpha");
        assertThat(ok.getPromptTokens()).isEqualTo(7);
        assertThat(ok.getCompletionTokens()).isEqualTo(3);
        assertThat(ok.getTotalTokens()).isEqualTo(10);
        assertThat(ok.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(records).anyMatch(r -> r.getStatus() == 500);

        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].name").value("alpha"))
                .andExpect(jsonPath("$.providers[0].state").value("cooldown"))
                .andExpect(jsonPath("$.providers[0].cooldown_reason").value("HTTP 500"))
                .andExpect(jsonPath("$.providers[0].requests_today").value(2))
                .andExpect(jsonPath("$.providers[0].tokens_today").value(10))
                .andExpect(jsonPath("$.providers[0].errors_today").value(1))
                .andExpect(jsonPath("$.providers[0].remaining_requests").value(999))
                .andExpect(jsonPath("$.providers[0].rpm_limit").value(100))
                .andExpect(jsonPath("$.providers[0].last_error").value("HTTP 500"))
                .andExpect(jsonPath("$.providers[1].name").value("beta"))
                .andExpect(jsonPath("$.providers[1].state").value("skipped"))
                .andExpect(jsonPath("$.today.requests").value(2))
                .andExpect(jsonPath("$.hourly.length()").value(24))
                .andExpect(jsonPath("$.recent.length()").value(2))
                .andExpect(jsonPath("$.recent[0].status").value(500));
    }

    @Test
    void streamSendsSnapshotOnConnectAndAfterEachRequest(@Autowired StatusBroadcaster broadcaster) throws Exception {
        MvcResult sse = mvc.perform(get("/status/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(sse.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        String first = sse.getResponse().getContentAsString();
        assertThat(first).contains("retry:3000").contains("event:snapshot").contains("\"providers\"");
        assertThat(count(first, "event:snapshot")).isEqualTo(1);

        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        String after = sse.getResponse().getContentAsString();
        assertThat(count(after, "event:snapshot")).isGreaterThanOrEqualTo(2);
        assertThat(after).contains("\"total_tokens\":10");

        broadcaster.heartbeat();
        assertThat(sse.getResponse().getContentAsString()).contains("event:heartbeat");
        assertThat(broadcaster.clients()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void dashboardIsServedAtRoot() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.containsString("EventSource"),
                        org.hamcrest.Matchers.containsString("app.js"))));
    }

    static int count(String s, String sub) {
        int c = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) {
            c++;
        }
        return c;
    }
}
