package ru.akiev.gateway.limits;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Полный контекст с ВКЛЮЧЁННЫМ опросом при старте: alpha отвечает (лимиты из заголовков), beta не отвечает
 * (порт 1 — соединение отклонено) и исключается. Проверяем /status (лимиты, источник, исключение),
 * учёт токенов после запроса и 413 для запроса больше TPM.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LimitsStatusIntegrationTest {

    static final MockWebServer upstream = new MockWebServer();

    static final String OK = """
            {"id":"x","object":"chat.completion","created":1,"model":"m-alpha",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
            """;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        upstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().endsWith("/models")) {
                    return new MockResponse().setBody("{\"data\":[]}").addHeader("x-ratelimit-limit-requests", "60");
                }
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(OK);
            }
        });
        upstream.start();
        String url = upstream.url("/v1").toString();
        // своя БД: счётчики восстанавливаются из usage_record, чужие записи «alpha» из других тестов не нужны
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:limits-status;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("gateway.limits.startup-probe", () -> "true");
        r.add("gateway.limits.probe-timeout", () -> "PT2S");
        r.add("gateway.providers[0].name", () -> "alpha");
        r.add("gateway.providers[0].base-url", () -> url);
        r.add("gateway.providers[0].api-key", () -> "k");
        r.add("gateway.providers[0].model", () -> "m-alpha");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[0].limits.rpm", () -> "100");
        r.add("gateway.providers[0].limits.tpm", () -> "5000");
        r.add("gateway.providers[1].name", () -> "beta");
        r.add("gateway.providers[1].base-url", () -> "http://localhost:1/v1");
        r.add("gateway.providers[1].api-key", () -> "k");
        r.add("gateway.providers[1].model", () -> "m-beta");
        r.add("gateway.providers[1].priority", () -> "2");
    }

    @AfterAll
    static void stop() throws IOException {
        upstream.shutdown();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void statusShowsLimitsSourcesAndExcludedProvider() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":20}"))
                .andExpect(status().isOk());

        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].name").value("alpha"))
                .andExpect(jsonPath("$.providers[0].excluded").value(false))
                .andExpect(jsonPath("$.providers[0].limits.rpm.limit").value(60))
                .andExpect(jsonPath("$.providers[0].limits.rpm.source").value("headers"))
                .andExpect(jsonPath("$.providers[0].limits.tpm.limit").value(5000))
                .andExpect(jsonPath("$.providers[0].limits.tpm.source").value("config"))
                // резерв (оценка) заменён фактическим usage = 10 токенов
                .andExpect(jsonPath("$.providers[0].limits.tpm.used").value(10))
                .andExpect(jsonPath("$.providers[0].limits.tpm.remaining").value(4990))
                .andExpect(jsonPath("$.providers[0].limits_probe.ok").value(true))
                .andExpect(jsonPath("$.providers[1].name").value("beta"))
                .andExpect(jsonPath("$.providers[1].state").value("excluded"))
                .andExpect(jsonPath("$.providers[1].excluded").value(true))
                .andExpect(jsonPath("$.providers[1].limits_probe.ok").value(false));
    }

    @Test
    void requestLargerThanTpmGets413() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":100000}"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("request_too_large"));
    }
}
