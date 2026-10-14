package ru.akiev.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
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
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/** stream=true: формат SSE OpenAI, [DONE], failover до первого чанка, учёт usage. */
@SpringBootTest
@AutoConfigureMockMvc
class StreamingIntegrationTest {

    static final MockWebServer alpha = new MockWebServer();
    static final MockWebServer beta = new MockWebServer();

    static String chunk(String content) {
        return "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m-up\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    static final String USAGE_CHUNK = "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"m-up\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";

    static MockResponse sse(String... data) {
        StringBuilder b = new StringBuilder();
        for (String d : data) {
            b.append("data: ").append(d).append("\n\n");
        }
        b.append("data: [DONE]\n\n");
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(b.toString());
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        alpha.start();
        beta.start();
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> alpha.url("/v1").toString());
        r.add("gateway.providers[0].api-key", () -> "ka");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[0].timeout", () -> "2s");
        r.add("gateway.providers[1].name", () -> "beta");
        r.add("gateway.providers[1].base-url", () -> beta.url("/v1").toString());
        r.add("gateway.providers[1].api-key", () -> "kb");
        r.add("gateway.providers[1].model", () -> "m-beta");
        r.add("gateway.providers[1].priority", () -> "2");
    }

    @AfterAll
    static void stop() throws IOException {
        alpha.shutdown();
        beta.shutdown();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    UsageRecordRepository repository;
    @Autowired
    ProviderRouter router;

    @BeforeEach
    void reset() throws InterruptedException {
        repository.deleteAll();
        router.providers().forEach(p -> p.state().resetCooldown());
        // новые диспетчеры = пустые очереди ответов; дочитываем записанные запросы прошлых тестов
        alpha.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());
        beta.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());
        while (alpha.takeRequest(10, TimeUnit.MILLISECONDS) != null) { }
        while (beta.takeRequest(10, TimeUnit.MILLISECONDS) != null) { }
    }


    String stream(String body) throws Exception {
        MvcResult r = mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(r)).andExpect(status().isOk());
        assertThat(r.getResponse().getContentType()).startsWith("text/event-stream");
        return r.getResponse().getContentAsString();
    }

    static final String BODY = "{\"model\":\"openai/coder\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    void proxiesChunksInOpenAiFormatEndingWithDone() throws Exception {
        alpha.enqueue(sse(chunk("При"), chunk("вет"), USAGE_CHUNK));
        String out = stream(BODY);

        List<String> events = List.of(out.split("\n\n"));
        assertThat(events).containsExactly("data: " + chunk("При"), "data: " + chunk("вет"), "data: [DONE]");
        assertThat(out).endsWith("data: [DONE]\n\n");
        // usage-чанк клиент не просил — не пересылаем, но учитываем

        RecordedRequest req = alpha.takeRequest();
        String sent = req.getBody().readUtf8();
        assertThat(sent).contains("\"stream\":true").contains("\"include_usage\":true").contains("\"model\":\"m-alpha\"");
        assertThat(req.getHeader("Accept")).contains("text/event-stream");

        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> repository.count() == 1);
        UsageRecord rec = repository.findAll().get(0);
        assertThat(rec.getProvider()).isEqualTo("alpha");
        assertThat(rec.getStatus()).isEqualTo(200);
        assertThat(rec.getPromptTokens()).isEqualTo(5);
        assertThat(rec.getTotalTokens()).isEqualTo(7);
    }

    @Test
    void forwardsUsageChunkWhenClientAsked() throws Exception {
        alpha.enqueue(sse(chunk("x"), USAGE_CHUNK));
        String out = stream("{\"stream\":true,\"stream_options\":{\"include_usage\":true},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(out).contains("\"total_tokens\":7").endsWith("data: [DONE]\n\n");
    }

    @Test
    void failoverBeforeFirstChunk() throws Exception {
        alpha.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30"));
        beta.enqueue(sse(chunk("from-beta")));
        String out = stream(BODY);
        assertThat(out).contains("from-beta").endsWith("data: [DONE]\n\n");
        assertThat(router.providers().get(0).state().isAvailable(java.time.Instant.now())).isFalse();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> repository.count() == 2);
        assertThat(repository.findAll()).extracting(UsageRecord::getStatus).containsExactlyInAnyOrder(429, 200);
    }

    @Test
    void timeoutBeforeFirstChunkFailsOver() throws Exception {
        alpha.enqueue(sse(chunk("slow")).setHeadersDelay(5, TimeUnit.SECONDS));
        beta.enqueue(sse(chunk("fast")));
        String out = stream(BODY);
        assertThat(out).contains("fast").doesNotContain("slow");
    }

    @Test
    void noFailoverAfterFirstChunk() throws Exception {
        // первый чанк пришёл сразу, следующий — через 3 с (> timeout 2 с): ошибка ПОСЛЕ начала ответа
        String first = "data: " + chunk("half") + "\n\n";
        alpha.enqueue(sse(chunk("half"), chunk("never"))
                .throttleBody(first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 3, TimeUnit.SECONDS));
        beta.enqueue(sse(chunk("must-not-be-used")));
        String out = stream(BODY);
        assertThat(out).contains("half").doesNotContain("never").doesNotContain("must-not-be-used")
                .contains("\"error\"").endsWith("data: [DONE]\n\n");
        assertThat(beta.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void allDownBeforeFirstChunkGives503() throws Exception {
        alpha.enqueue(new MockResponse().setResponseCode(500));
        beta.enqueue(new MockResponse().setResponseCode(503));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("all_providers_unavailable"));
    }

    @Test
    void nonStreamStillWorks() throws Exception {
        alpha.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("ok"));
    }
}
