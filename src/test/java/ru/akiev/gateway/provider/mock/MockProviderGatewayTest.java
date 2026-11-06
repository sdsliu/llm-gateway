package ru.akiev.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Каждый сценарий mock-провайдера через настоящий шлюз (MockMvc): статус, тело и usage_record по x-run-id.
 * Каталог {@code gateway.mock.scenarios-dir} перекрывает classpath-файл diff-ok маркером SCENARIO_FROM_DIRECTORY.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mockgw;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.limits.startup-probe=true",
        "gateway.limits.probe-timeout=PT1S",
        "gateway.mock.exclusive=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class MockProviderGatewayTest {

    static final String MARKER = "SCENARIO_FROM_DIRECTORY";
    static final String BAD_JSON = "{\"id\":\"chatcmpl-bad\",\"object\":\"chat.completion\",\"choices\":[";

    static final Path DIR = createDir();

    static Path createDir() {
        try {
            Path dir = Files.createTempDirectory("mock-scenarios-it");
            Files.writeString(dir.resolve("diff-ok.json"), """
                    {"mode":"completion","content":"src/main/java/demo/Hello.java\\n<<<<<<< SEARCH\\n        return \\"hello\\";\\n=======\\n        return \\"hello, world\\";\\n>>>>>>> REPLACE\\nSCENARIO_FROM_DIRECTORY\\n","usage":{"prompt_tokens":21,"completion_tokens":13,"total_tokens":34}}
                    """);
            return dir;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void dir(DynamicPropertyRegistry registry) {
        registry.add("gateway.mock.scenarios-dir", () -> DIR.toString());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ProviderRouter router;
    @Autowired
    UsageRecordRepository repository;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        repository.deleteAll();
        router.providers().forEach(p -> p.state().resetCooldown());
    }

    @Test
    void onlyMockProviderAndItStaysInRotation() throws Exception {
        assertThat(router.names()).containsExactly("mock");
        assertThat(router.providers()).singleElement().isInstanceOf(MockProvider.class);
        LlmProvider mock = router.providers().get(0);
        assertThat(mock.state().excluded()).isFalse();
        assertThat(mock.models()).contains("mock", "gpt-oss-120b");

        mvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='mock')]").exists());
        mvc.perform(post("/status/providers/mock/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("up"))
                .andExpect(jsonPath("$.http_status").value(200))
                .andExpect(jsonPath("$.provider").value("mock"));
    }

    @Test
    void diffOkNonStreamRecordsUsageByRunId() throws Exception {
        JsonNode body = read(exchange("diff-ok", "run-diff-ok", "ai-runner", body("gpt-oss-120b", false, false))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(body.at("/choices/0/message/content").asText())
                .contains("<<<<<<< SEARCH", ">>>>>>> REPLACE", "src/main/java/demo/Hello.java", MARKER);
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(34);
        assertThat(body.at("/model").asText()).isEqualTo("gpt-oss-120b");

        UsageRecord row = onlyRow();
        assertThat(row.getProvider()).isEqualTo("mock");
        assertThat(row.getRunId()).isEqualTo("run-diff-ok");
        assertThat(row.getClient()).isEqualTo("ai-runner");
        assertThat(row.getStatus()).isEqualTo(200);
        assertThat(row.getPromptTokens()).isEqualTo(21);
        assertThat(row.getCompletionTokens()).isEqualTo(13);
        assertThat(row.getTotalTokens()).isEqualTo(34);

        mvc.perform(get("/stats/runs").param("groupBy", "run").param("days", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("run-diff-ok"))
                .andExpect(jsonPath("$[0].total_tokens").value(34))
                .andExpect(jsonPath("$[0].providers.mock").value(1));
    }

    @Test
    void diffOkStreamCarriesUsage() throws Exception {
        String sse = stream("diff-ok", "run-diff-ok-stream", body("openai/gpt-oss-120b", true, true));
        assertThat(sse).contains(MARKER, "<<<<<<< SEARCH", ">>>>>>> REPLACE", "\"total_tokens\":34")
                .endsWith("data: [DONE]\n\n");
        UsageRecord row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(200);
        assertThat(row.getRunId()).isEqualTo("run-diff-ok-stream");
        assertThat(row.getTotalTokens()).isEqualTo(34);
        assertThat(row.getModel()).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void diffBroken() throws Exception {
        String content = contentOf(exchange("diff-broken", "run-diff-broken", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).contains("MOCK_DIFF_BROKEN", "<<<<<<< SEARCH").doesNotContain(">>>>>>> REPLACE");
        assertThat(onlyRow().getTotalTokens()).isEqualTo(31);
    }

    @Test
    void editTest() throws Exception {
        String content = contentOf(exchange("edit-test", "run-edit-test", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).contains("src/test/java/demo/HelloTest.java", "assertEquals", ">>>>>>> REPLACE");
        assertThat(onlyRow().getTotalTokens()).isEqualTo(29);
    }

    @Test
    void editPomAddsSkipTests() throws Exception {
        String content = contentOf(exchange("edit-pom", "run-edit-pom", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).contains("pom.xml", "<skipTests>true</skipTests>");
        assertThat(onlyRow().getTotalTokens()).isEqualTo(23);
    }

    @Test
    void empty() throws Exception {
        JsonNode body = read(exchange("empty", "run-empty", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.at("/choices/0/message/content").asText()).isEmpty();
        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(5);
        assertThat(onlyRow().getTotalTokens()).isEqualTo(5);
    }

    @Test
    void err429HasRetryAfterAndDoesNotStick() throws Exception {
        exchange("err-429", "run-429", null, body("mock", false, false))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andExpect(jsonPath("$.error.type").value("provider_error"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("err-429")));
        UsageRecord row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(429);
        assertThat(row.getRunId()).isEqualTo("run-429");
        assertThat(row.getTotalTokens()).isNull();
        assertThat(router.providers().get(0).state().isAvailable(java.time.Instant.now())).isTrue();

        repository.deleteAll();
        exchange("empty", "run-after-429", null, body("mock", false, false)).andExpect(status().isOk());
    }

    @Test
    void err503() throws Exception {
        exchange("err-503", "run-503", null, body("mock", false, false))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.type").value("provider_error"))
                .andExpect(jsonPath("$.error.code").value("503"));
        assertThat(onlyRow().getStatus()).isEqualTo(503);
        assertThat(onlyRow().getRunId()).isEqualTo("run-503");
    }

    @Test
    void err413() throws Exception {
        exchange("err-413", "run-413", null, body("mock", false, false))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.type").value("request_too_large"));
        UsageRecord row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(413);
        assertThat(row.getRunId()).isEqualTo("run-413");
        assertThat(row.getTotalTokens()).isNull();
    }

    @Test
    void timeoutWaitsThen504() throws Exception {
        long started = System.nanoTime();
        exchange("timeout", "run-timeout", null, body("mock", false, false))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.type").value("provider_error"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("timed out")));
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertThat(ms).isBetween(300L, 5_000L);
        assertThat(onlyRow().getStatus()).isEqualTo(504);
        assertThat(onlyRow().getRunId()).isEqualTo("run-timeout");
    }

    @Test
    void streamCutDropsAfterFirstChunk() throws Exception {
        JsonNode nonStream = read(exchange("stream-cut", "run-cut-sync", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(nonStream.at("/choices/0/message/content").asText()).isEqualTo("MOCK_STREAM_CUT_HEAD");
        assertThat(nonStream.at("/usage/total_tokens").asInt()).isEqualTo(11);

        String sse = stream("stream-cut", "run-cut", body("mock", true, false));
        assertThat(sse).contains("MOCK_STREAM_CUT_HEAD").doesNotContain("MOCK_STREAM_CUT_TAIL")
                .contains("\"error\"").contains("stream-cut").endsWith("data: [DONE]\n\n");
        assertThat(repository.findAll()).extracting(UsageRecord::getStatus).containsExactlyInAnyOrder(200, 502);
        assertThat(repository.findAll()).extracting(UsageRecord::getRunId)
                .containsExactlyInAnyOrder("run-cut-sync", "run-cut");
    }

    @Test
    void badJsonNonStreamAndStream() throws Exception {
        MvcResult raw = exchange("bad-json", "run-bad", null, body("mock", false, false))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(raw.getResponse().getContentAsString()).isEqualTo(BAD_JSON);
        assertThat(raw.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        UsageRecord sync = repository.findAll().get(0);
        assertThat(sync.getStatus()).isEqualTo(200);
        assertThat(sync.getRunId()).isEqualTo("run-bad");
        assertThat(sync.getTotalTokens()).isEqualTo(7);

        String sse = stream("bad-json", "run-bad-stream", body("mock", true, true));
        assertThat(sse).contains(BAD_JSON).contains("\"total_tokens\":7").endsWith("data: [DONE]\n\n");
        assertThat(repository.findAll()).filteredOn(r -> "run-bad-stream".equals(r.getRunId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(200);
                    assertThat(r.getTotalTokens()).isEqualTo(7);
                });
    }

    @Test
    void scenarioComesFromRunIdWhenHeaderMissing() throws Exception {
        String content = contentOf(exchange(null, "20261106-101500-edit-pom", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).contains("<skipTests>true</skipTests>");
        assertThat(onlyRow().getRunId()).isEqualTo("20261106-101500-edit-pom");
    }

    @Test
    void headerOverridesRunId() throws Exception {
        String content = contentOf(exchange("empty", "run-diff-ok", null, body("mock", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).isEmpty();
    }

    @Test
    void unknownScenarioIs400() throws Exception {
        exchange("no-such-scenario", "run-unknown", null, body("mock", false, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("provider_error"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Unknown mock scenario")));
        assertThat(onlyRow().getStatus()).isEqualTo(400);
    }

    @Test
    void defaultScenarioWhenRunIdDoesNotNameOne() throws Exception {
        String content = contentOf(exchange(null, "plain-run", null, body("gpt-oss-120b", false, false))
                .andExpect(status().isOk()).andReturn());
        assertThat(content).contains(MARKER, "<<<<<<< SEARCH");
        assertThat(onlyRow().getRunId()).isEqualTo("plain-run");
        assertThat(onlyRow().getTotalTokens()).isEqualTo(34);
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String scenario, String runId, String client,
            String jsonBody) throws Exception {
        MockHttpServletRequestBuilder request = post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody);
        if (scenario != null) {
            request.header("x-mock-scenario", scenario);
        }
        if (runId != null) {
            request.header("x-run-id", runId);
        }
        if (client != null) {
            request.header("x-client", client);
        }
        return mvc.perform(request);
    }

    private static String body(String model, boolean stream, boolean usage) {
        String streamPart = stream
                ? ",\"stream\":true" + (usage ? ",\"stream_options\":{\"include_usage\":true}" : "")
                : "";
        return "{\"model\":\"" + model + "\"" + streamPart
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private String stream(String scenario, String runId, String jsonBody) throws Exception {
        MvcResult result = exchange(scenario, runId, null, jsonBody)
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        return result.getResponse().getContentAsString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String contentOf(MvcResult result) throws Exception {
        return read(result).at("/choices/0/message/content").asText();
    }

    private UsageRecord onlyRow() {
        List<UsageRecord> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
