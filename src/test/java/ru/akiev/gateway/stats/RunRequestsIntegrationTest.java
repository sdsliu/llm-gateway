package ru.akiev.gateway.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.RequestTags;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Учёт запроса для экрана «Задачи» (issue #45): x-attempt, размер контекста без usage,
 * failover с общим request_id, список запросов прогона и фильтр runId.
 * Провайдеры — MockWebServer, схема — H2 + Flyway V4.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunRequestsIntegrationTest {

    static final MockWebServer ALPHA = new MockWebServer();
    static final MockWebServer BETA = new MockWebServer();

    /** Ответ, который сервер отдаст на следующий запрос. Меняется из теста, очередь не копится. */
    static volatile Supplier<MockResponse> alphaResponse = RunRequestsIntegrationTest::ok;
    static volatile Supplier<MockResponse> betaResponse = RunRequestsIntegrationTest::ok;

    static final String OK = """
            {"id":"x","object":"chat.completion","created":1,"model":"m-alpha",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":700,"completion_tokens":300,"total_tokens":1000}}
            """;

    /** system "abcd" (4) + user "hi" (2) → 6 символов, 2 сообщения, оценка входа 2+8 = 10. */
    static final String PLAIN = """
            {"max_tokens":128,"messages":[{"role":"system","content":"abcd"},{"role":"user","content":"hi"}]}""";

    static final String STREAM = """
            {"stream":true,"max_tokens":32,"messages":[{"role":"system","content":"abcd"},{"role":"user","content":"hi"}]}""";

    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        ALPHA.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return alphaResponse.get();
            }
        });
        BETA.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return betaResponse.get();
            }
        });
        ALPHA.start();
        BETA.start();
        r.add("gateway.routing.mode", () -> "priority");
        r.add("gateway.efficiency.runner-stats-url", () -> "");
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> ALPHA.url("/v1").toString());
        r.add("gateway.providers[0].api-key", () -> "k");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[1].name", () -> "beta");
        r.add("gateway.providers[1].base-url", () -> BETA.url("/v1").toString());
        r.add("gateway.providers[1].api-key", () -> "k");
        r.add("gateway.providers[1].model", () -> "m-beta");
        r.add("gateway.providers[1].priority", () -> "2");
    }

    @AfterAll
    static void stop() throws IOException {
        ALPHA.shutdown();
        BETA.shutdown();
    }

    static MockResponse ok() {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(OK);
    }

    static MockResponse sse() {
        String body = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m-alpha\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m-alpha\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}\n\ndata: [DONE]\n\n";
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    static MockResponse down() {
        String huge = "E".repeat(400);
        return new MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"" + huge + "\"}}");
    }

    /** 413 без объекта usage — как раз случай «не влезло, токенов в ответе нет». */
    static MockResponse tooLarge() {
        return new MockResponse().setResponseCode(413).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Request too large\"}}");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    UsageRecordRepository repository;
    @Autowired
    ProviderRouter router;

    @BeforeEach
    void clean() throws Exception {
        alphaResponse = RunRequestsIntegrationTest::ok;
        betaResponse = RunRequestsIntegrationTest::ok;
        repository.deleteAll();
        router.providers().forEach(p -> p.state().resetCooldown());
        mvc.perform(delete("/status/routing")).andExpect(status().isOk());
    }

    @Test
    void plainRequestStoresAttemptAndContextSize() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "r1").header("x-attempt", "2")
                        .content(PLAIN))
                .andExpect(status().isOk());

        mvc.perform(get("/stats/runs/r1/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value("r1"))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].attempt").value(2))
                .andExpect(jsonPath("$.requests[0].failover_index").value(0))
                .andExpect(jsonPath("$.requests[0].messages_count").value(2))
                .andExpect(jsonPath("$.requests[0].request_chars").value(6))
                .andExpect(jsonPath("$.requests[0].est_prompt_tokens").value(10))
                .andExpect(jsonPath("$.requests[0].max_tokens").value(128))
                .andExpect(jsonPath("$.requests[0].stream").value(false))
                .andExpect(jsonPath("$.requests[0].status").value(200))
                .andExpect(jsonPath("$.requests[0].prompt_tokens").value(700))
                .andExpect(jsonPath("$.requests[0].provider").value("alpha"))
                .andExpect(jsonPath("$.requests[0].request_id").isNotEmpty())
                .andExpect(jsonPath("$.totals.requests").value(1))
                .andExpect(jsonPath("$.totals.errors").value(0))
                .andExpect(jsonPath("$.totals.prompt_tokens").value(700))
                .andExpect(jsonPath("$.totals.max_prompt_tokens").value(700))
                .andExpect(jsonPath("$.totals.by_provider.alpha").value(1))
                .andExpect(jsonPath("$.totals.by_attempt['2']").value(1));

        mvc.perform(get("/stats/runs").param("runId", "r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attempts[0]").value(2))
                .andExpect(jsonPath("$[0].max_prompt_tokens").value(700));
    }

    @Test
    void nonNumericAttemptIsStoredAsNullAndRequestStillSucceeds() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "bad-attempt").header("x-attempt", "second")
                        .content(PLAIN))
                .andExpect(status().isOk());
        mvc.perform(get("/stats/runs/bad-attempt/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].attempt").doesNotExist())
                .andExpect(jsonPath("$.requests[0].est_prompt_tokens").value(10));
    }

    @Test
    void streamRequestStoresMetricsToo() throws Exception {
        alphaResponse = RunRequestsIntegrationTest::sse;
        MvcResult async = mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "s1").header("x-attempt", "2")
                        .content(STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        mvc.perform(get("/stats/runs/s1/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].attempt").value(2))
                .andExpect(jsonPath("$.requests[0].stream").value(true))
                .andExpect(jsonPath("$.requests[0].messages_count").value(2))
                .andExpect(jsonPath("$.requests[0].request_chars").value(6))
                .andExpect(jsonPath("$.requests[0].est_prompt_tokens").value(10))
                .andExpect(jsonPath("$.requests[0].prompt_tokens").value(9))
                .andExpect(jsonPath("$.requests[0].total_tokens").value(12))
                .andExpect(jsonPath("$.requests[0].status").value(200));
    }

    @Test
    void failover503Then200SharesRequestIdAndKeepsEstimate() throws Exception {
        alphaResponse = RunRequestsIntegrationTest::down;
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "fail").header("x-attempt", "2")
                        .content(PLAIN))
                .andExpect(status().isOk());

        JsonNode body = JSON.readTree(mvc.perform(get("/stats/runs/fail/requests"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("requests").size()).isEqualTo(2);
        JsonNode first = body.get("requests").get(0);
        JsonNode second = body.get("requests").get(1);
        assertThat(first.get("status").asInt()).isEqualTo(503);
        assertThat(second.get("status").asInt()).isEqualTo(200);
        assertThat(first.get("failover_index").asInt()).isEqualTo(0);
        assertThat(second.get("failover_index").asInt()).isEqualTo(1);
        assertThat(first.get("provider").asText()).isEqualTo("alpha");
        assertThat(second.get("provider").asText()).isEqualTo("beta");
        assertThat(first.get("request_id").asText()).isEqualTo(second.get("request_id").asText()).hasSize(36);
        assertThat(first.get("attempt").asInt()).isEqualTo(2);
        assertThat(second.get("attempt").asInt()).isEqualTo(2);
        assertThat(first.get("est_prompt_tokens").asInt()).isEqualTo(10);
        assertThat(first.has("prompt_tokens")).isFalse();
        assertThat(second.get("prompt_tokens").asInt()).isEqualTo(700);
        assertThat(first.get("messages_count").asInt()).isEqualTo(2);
        assertThat(first.get("request_chars").asInt()).isEqualTo(6);
        assertThat(first.get("stream").asBoolean()).isFalse();
        assertThat(first.get("error").asText()).hasSize(300).startsWith("Provider alpha returned 503");
        assertThat(body.get("totals").get("requests").asInt()).isEqualTo(2);
        assertThat(body.get("totals").get("errors").asInt()).isEqualTo(1);
        assertThat(body.get("totals").get("by_provider").get("alpha").asInt()).isEqualTo(1);
        assertThat(body.get("totals").get("by_provider").get("beta").asInt()).isEqualTo(1);
        assertThat(body.get("totals").get("by_attempt").get("2").asInt()).isEqualTo(2);
        // Успех прислал 700, ошибка — только оценку 10: максимум по факту
        assertThat(body.get("totals").get("max_prompt_tokens").asInt()).isEqualTo(700);
    }

    @Test
    void provider413WithoutUsageStillStoresEstimate() throws Exception {
        alphaResponse = RunRequestsIntegrationTest::tooLarge;
        betaResponse = RunRequestsIntegrationTest::tooLarge;
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "big").header("x-attempt", "1")
                        .content(PLAIN))
                .andExpect(status().is(413));

        JsonNode body = JSON.readTree(mvc.perform(get("/stats/runs/big/requests"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("requests").size()).isEqualTo(2);
        assertThat(body.get("requests").get(0).get("request_id").asText())
                .isEqualTo(body.get("requests").get(1).get("request_id").asText());
        body.withArray("requests").forEach(row -> {
            assertThat(row.get("status").asInt()).isEqualTo(413);
            assertThat(row.has("prompt_tokens")).isFalse();
            assertThat(row.get("est_prompt_tokens").asInt()).isEqualTo(10);
            assertThat(row.get("messages_count").asInt()).isEqualTo(2);
            assertThat(row.get("request_chars").asInt()).isEqualTo(6);
            assertThat(row.get("attempt").asInt()).isEqualTo(1);
        });
        assertThat(body.get("requests").get(0).get("failover_index").asInt()).isEqualTo(0);
        assertThat(body.get("requests").get(1).get("failover_index").asInt()).isEqualTo(1);
        assertThat(body.get("totals").get("max_prompt_tokens").asInt()).isEqualTo(10);
        assertThat(body.get("totals").get("prompt_tokens").asInt()).isZero();

        mvc.perform(get("/stats/runs").param("runId", "big"))
                .andExpect(jsonPath("$[0].max_prompt_tokens").value(10))
                .andExpect(jsonPath("$[0].prompt_tokens").value(0))
                .andExpect(jsonPath("$[0].attempts[0]").value(1));
    }

    @Test
    void attemptFilterAndUnknownRun() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "filt").header("x-attempt", "1").content(PLAIN))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "filt").header("x-attempt", "2").content(PLAIN))
                .andExpect(status().isOk());

        mvc.perform(get("/stats/runs/filt/requests").param("attempt", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].attempt").value(1))
                .andExpect(jsonPath("$.totals.requests").value(1));
        mvc.perform(get("/stats/runs/filt/requests"))
                .andExpect(jsonPath("$.requests.length()").value(2));
        mvc.perform(get("/stats/runs/filt/requests").param("attempt", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").isEmpty())
                .andExpect(jsonPath("$.totals.requests").value(0));
        mvc.perform(get("/stats/runs/does-not-exist/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value("does-not-exist"))
                .andExpect(jsonPath("$.requests").isEmpty())
                .andExpect(jsonPath("$.totals.requests").value(0))
                .andExpect(jsonPath("$.totals.max_prompt_tokens").value(0));
        mvc.perform(get("/stats/runs/filt/requests").param("attempt", "foo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runIdFilterIgnoresDaysWindow() throws Exception {
        for (String id : new String[] {"r1", "r2", "r3"}) {
            mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                            .header("x-run-id", id).header("x-attempt", "1").content(PLAIN))
                    .andExpect(status().isOk());
        }
        repository.save(new UsageRecord("alpha", "m", 123, 4, 127, 200, 8, null,
                Instant.now().minus(Duration.ofDays(10)), new RequestTags("old-run", null, null, null)));

        JsonNode window = JSON.readTree(mvc.perform(get("/stats/runs").param("days", "1").param("limit", "1000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(window.findValuesAsText("key")).contains("r1", "r2", "r3").doesNotContain("old-run");

        JsonNode filtered = JSON.readTree(mvc.perform(get("/stats/runs").param("runId", "r1, r2").param("days", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.findValuesAsText("key")).containsExactlyInAnyOrder("r1", "r2");

        mvc.perform(get("/stats/runs").param("runId", "old-run").param("days", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("old-run"))
                .andExpect(jsonPath("$[0].prompt_tokens").value(123))
                .andExpect(jsonPath("$[0].max_prompt_tokens").value(123))
                .andExpect(jsonPath("$[0].attempts").isEmpty());

        JsonNode blank = JSON.readTree(mvc.perform(get("/stats/runs").param("runId", " ").param("days", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(blank.findValuesAsText("key")).contains("r1").doesNotContain("old-run");
    }

    @Test
    void legacyNullColumnsDoNotBreakStatsOrEfficiencyPage() throws Exception {
        repository.save(new UsageRecord("alpha", "m", null, null, null, 500, 3, "e".repeat(400), Instant.now()));
        repository.save(new UsageRecord("alpha", "m", null, null, null, 503, 4, null, Instant.now(),
                new RequestTags("legacy", null, null, null)));

        JsonNode runs = JSON.readTree(mvc.perform(get("/stats/runs").param("days", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(runs.size()).isGreaterThanOrEqualTo(1);
        runs.forEach(row -> {
            assertThat(row.get("attempts").isArray()).isTrue();
            assertThat(row.get("attempts").size()).isZero();
            assertThat(row.get("max_prompt_tokens").asInt()).isZero();
            assertThat(row.get("clients").isArray()).isTrue();
            assertThat(row.get("providers").isObject()).isTrue();
        });

        JsonNode list = JSON.readTree(mvc.perform(get("/stats/runs/legacy/requests"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("requests").size()).isEqualTo(1);
        JsonNode row = list.get("requests").get(0);
        assertThat(row.get("status").asInt()).isEqualTo(503);
        assertThat(row.has("attempt")).isFalse();
        assertThat(row.has("est_prompt_tokens")).isFalse();
        assertThat(row.has("messages_count")).isFalse();
        assertThat(row.has("request_chars")).isFalse();
        assertThat(row.has("stream")).isFalse();
        assertThat(row.has("prompt_tokens")).isFalse();
        assertThat(list.get("totals").get("requests").asInt()).isEqualTo(1);
        assertThat(list.get("totals").get("errors").asInt()).isEqualTo(1);
        assertThat(list.get("totals").get("max_prompt_tokens").asInt()).isZero();

        JsonNode eff = JSON.readTree(mvc.perform(get("/stats/efficiency"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(eff.get("runner_available").asBoolean()).isFalse();
        assertThat(eff.get("other").size()).isGreaterThanOrEqualTo(1);
        eff.withArray("other").forEach(other -> {
            assertThat(other.get("attempts").isArray()).isTrue();
            assertThat(other.get("max_prompt_tokens").isNumber()).isTrue();
        });

        mvc.perform(get("/efficiency.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/stats/runs")))
                .andExpect(content().string(containsString("efficiency.js")));
    }
}
