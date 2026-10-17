package ru.akiev.gateway.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Полный путь: заголовки x-run-id / x-issue / x-client → usage_record (H2 + Flyway V2) → GET /stats/runs,
 * и GET /stats/efficiency со склейкой с фейковым ai-runner (MockWebServer вместо spring-runner).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatsIntegrationTest {

    static final MockWebServer upstream = new MockWebServer();
    static final MockWebServer runner = new MockWebServer();

    static final String OK = """
            {"id":"x","object":"chat.completion","created":1,"model":"m-alpha",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":700,"completion_tokens":300,"total_tokens":1000}}
            """;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        upstream.start();
        runner.start();
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> upstream.url("/v1").toString());
        r.add("gateway.providers[0].api-key", () -> "k");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.efficiency.runner-stats-url", () -> runner.url("/stats").toString());
    }

    @AfterAll
    static void stop() throws IOException {
        upstream.shutdown();
        runner.shutdown();
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
    void tagsFromHeadersAndBodyAreStoredAndAggregated() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "20261017-1-42").header("x-issue", "42").header("x-client", "ai-runner")
                        .content("{\"user\":\"aider\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());

        var saved = repository.findAll().get(0);
        assertThat(saved.getRunId()).isEqualTo("20261017-1-42");
        assertThat(saved.getIssue()).isEqualTo("42");
        assertThat(saved.getClient()).isEqualTo("ai-runner");
        assertThat(saved.getRequestUser()).isEqualTo("aider");

        mvc.perform(get("/stats/runs?groupBy=run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("20261017-1-42"))
                .andExpect(jsonPath("$[0].requests").value(1))
                .andExpect(jsonPath("$[0].prompt_tokens").value(700))
                .andExpect(jsonPath("$[0].providers.alpha").value(1))
                .andExpect(jsonPath("$[0].cost[0].model").value("gpt-4o"));
        mvc.perform(get("/stats/runs?groupBy=nope")).andExpect(status().isBadRequest());
    }

    @Test
    void streamRequestsAreTaggedToo() throws Exception {
        String sse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\ndata: [DONE]\n\n";
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse));
        MvcResult r = mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("x-run-id", "stream-run").header("x-client", "project-sage")
                        .content("{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(r)).andExpect(status().isOk());

        var saved = repository.findAll().get(0);
        assertThat(saved.getRunId()).as("метки дошли из потока стрима").isEqualTo("stream-run");
        assertThat(saved.getClient()).isEqualTo("project-sage");
        assertThat(saved.getTotalTokens()).isEqualTo(7);
    }

    @Test
    void efficiencyMergesGatewayStatsWithRunnerOutcomes() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(OK));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).header("x-run-id", "run-7")
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).andExpect(status().isOk());
        runner.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"summary":{},"runs":[{"runId":"run-7","issue":7,"status":"SUCCESS","attempts":2,
                  "prUrl":"https://github.com/sdsliu/app/pull/9"}]}"""));

        mvc.perform(get("/stats/efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runner_available").value(true))
                .andExpect(jsonPath("$.runs[0].run_id").value("run-7"))
                .andExpect(jsonPath("$.runs[0].total_tokens").value(1000))
                .andExpect(jsonPath("$.runs[0].pr_url").value("https://github.com/sdsliu/app/pull/9"))
                .andExpect(jsonPath("$.summary.success_rate").value(1.0))
                .andExpect(jsonPath("$.pricing_note").exists());

        // runner упал — страница всё равно работает, только без итогов прогонов
        runner.enqueue(new MockResponse().setResponseCode(500));
        mvc.perform(get("/stats/efficiency"))
                .andExpect(jsonPath("$.runner_available").value(false))
                .andExpect(jsonPath("$.runner_error").value("ai-runner ответил HTTP 500"))
                .andExpect(jsonPath("$.other[0].key").value("run-7"));
    }

    @Test
    void efficiencyPageIsServed() throws Exception {
        mvc.perform(get("/efficiency.html")).andExpect(status().isOk());
    }
}
