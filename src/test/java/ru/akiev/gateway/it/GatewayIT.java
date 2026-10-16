package ru.akiev.gateway.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.akiev.gateway.router.ProviderRouter;

/**
 * Сквозные интеграционные тесты: настоящий HTTP-сервер шлюза (случайный порт),
 * настоящий PostgreSQL 16 в Docker (Testcontainers, миграции Flyway) и два провайдера на WireMock.
 * Запуск: ./mvnw verify (нужен Docker). Реальные API-ключи не нужны.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

    /** @ServiceConnection сам подставит spring.datasource.url/username/password контейнера. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer PRIMARY = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer SECONDARY = new WireMockServer(wireMockConfig().dynamicPort());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PRIMARY.start();
        SECONDARY.start();
        r.add("gateway.providers[0].name", () -> "primary");
        r.add("gateway.providers[0].base-url", () -> PRIMARY.baseUrl() + "/v1");
        r.add("gateway.providers[0].api-key", () -> "key-primary");
        r.add("gateway.providers[0].model", () -> "model-primary");
        r.add("gateway.providers[0].priority", () -> "1");
        r.add("gateway.providers[0].limits.rpm", () -> "50");
        r.add("gateway.providers[1].name", () -> "secondary");
        r.add("gateway.providers[1].base-url", () -> SECONDARY.baseUrl() + "/v1");
        r.add("gateway.providers[1].api-key", () -> "key-secondary");
        r.add("gateway.providers[1].model", () -> "model-secondary");
        r.add("gateway.providers[1].priority", () -> "2");
        r.add("gateway.providers[2].name", () -> "nokey");
        r.add("gateway.providers[2].base-url", () -> "http://localhost:1/v1");
        r.add("gateway.providers[2].api-key", () -> "");
        r.add("gateway.providers[2].model", () -> "m");
        r.add("gateway.providers[2].priority", () -> "3");
    }

    @AfterAll
    static void stopWireMock() {
        PRIMARY.stop();
        SECONDARY.stop();
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProviderRouter router;
    final ObjectMapper json = new ObjectMapper();

    static final String COMPLETION = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"%s",
             "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":4,"total_tokens":15}}""";

    static String chunk(String model, String text) {
        return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}";
    }

    static String sseBody(String model, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            b.append("data: ").append(chunk(model, p)).append("\n\n");
        }
        b.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"").append(model)
                .append("\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}\n\n");
        return b.append("data: [DONE]\n\n").toString();
    }

    @BeforeEach
    void reset() {
        PRIMARY.resetAll();
        SECONDARY.resetAll();
        router.providers().forEach(p -> p.state().resetCooldown());
        jdbc.update("DELETE FROM usage_record");
    }

    ResponseEntity<String> chat(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/v1/chat/completions", new HttpEntity<>(body, h), String.class);
    }

    static final String NON_STREAM = "{\"model\":\"openai/coder\",\"messages\":[{\"role\":\"user\",\"content\":\"Привет\"}]}";
    static final String STREAM = "{\"model\":\"openai/coder\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Привет\"}]}";

    @Test
    void flywayCreatedSchemaInRealPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 16");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void nonStreamEndToEnd() throws Exception {
        PRIMARY.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withHeader("x-ratelimit-remaining-requests", "77")
                .withHeader("x-ratelimit-remaining-tokens", "5000")
                .withBody(COMPLETION.formatted("model-primary", "Здравствуйте"))));

        ResponseEntity<String> resp = chat(NON_STREAM);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(resp.getBody());
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("Здравствуйте");
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(15);

        PRIMARY.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer key-primary"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-primary"))));

        var row = jdbc.queryForMap("select * from usage_record");
        assertThat(row).containsEntry("provider", "primary").containsEntry("status", 200)
                .containsEntry("prompt_tokens", 11).containsEntry("completion_tokens", 4).containsEntry("total_tokens", 15);

        JsonNode status = json.readTree(http.getForObject("/status", String.class));
        JsonNode primary = status.at("/providers/0");
        assertThat(primary.get("name").asText()).isEqualTo("primary");
        assertThat(primary.get("state").asText()).isEqualTo("healthy");
        assertThat(primary.get("requests_today").asInt()).isEqualTo(1);
        assertThat(primary.get("tokens_today").asInt()).isEqualTo(15);
        assertThat(primary.get("remaining_requests").asInt()).isEqualTo(77);
        assertThat(primary.get("remaining_tokens").asInt()).isEqualTo(5000);
        assertThat(primary.get("rpm_limit").asInt()).isEqualTo(50);
        assertThat(primary.get("rpm_used").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(status.at("/providers/2/state").asText()).isEqualTo("skipped");
        assertThat(status.at("/today/tokens").asInt()).isEqualTo(15);
    }

    @Test
    void streamEndToEnd() {
        PRIMARY.stubFor(post("/v1/chat/completions").withRequestBody(containing("\"stream\":true"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody("model-primary", "Здрав", "ствуйте"))));

        ResponseEntity<String> resp = chat(STREAM);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        List<String> events = List.of(resp.getBody().split("\n\n"));
        assertThat(events).containsExactly(
                "data: " + chunk("model-primary", "Здрав"),
                "data: " + chunk("model-primary", "ствуйте"),
                "data: [DONE]");

        PRIMARY.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                jdbc.queryForObject("select count(*) from usage_record", Integer.class) == 1);
        var row = jdbc.queryForMap("select * from usage_record");
        assertThat(row).containsEntry("provider", "primary").containsEntry("status", 200).containsEntry("total_tokens", 12);
    }

    @Test
    void failover429ToSecondProviderNonStream() throws Exception {
        PRIMARY.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "120").withBody("{\"error\":{\"message\":\"rate limited\"}}")));
        SECONDARY.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(COMPLETION.formatted("model-secondary", "от второго"))));

        ResponseEntity<String> resp = chat(NON_STREAM);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(resp.getBody()).at("/choices/0/message/content").asText()).isEqualTo("от второго");

        // второй запрос: primary в cooldown → к нему не ходим
        chat(NON_STREAM);
        assertThat(PRIMARY.countRequestsMatching(postRequestedFor(urlEqualTo("/v1/chat/completions")).build()).getCount())
                .isEqualTo(1);

        assertThat(jdbc.queryForList("select provider || ':' || status as s from usage_record order by id", String.class))
                .containsExactly("primary:429", "secondary:200", "secondary:200");

        JsonNode status = json.readTree(http.getForObject("/status", String.class));
        assertThat(status.at("/providers/0/state").asText()).isEqualTo("cooldown");
        assertThat(status.at("/providers/0/cooldown_reason").asText()).isEqualTo("HTTP 429");
        assertThat(status.at("/providers/0/errors_today").asInt()).isEqualTo(1);
        assertThat(status.at("/providers/1/state").asText()).isEqualTo("healthy");
        assertThat(status.at("/providers/1/requests_today").asInt()).isEqualTo(2);
        assertThat(status.at("/recent/0/provider").asText()).isEqualTo("secondary");
    }

    @Test
    void failover429ToSecondProviderStream() {
        PRIMARY.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)));
        SECONDARY.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody(sseBody("model-secondary", "второй"))));

        ResponseEntity<String> resp = chat(STREAM);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("второй").endsWith("data: [DONE]\n\n");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                jdbc.queryForObject("select count(*) from usage_record", Integer.class) == 2);
    }

    @Test
    void allProvidersDownGives503() throws Exception {
        PRIMARY.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(500)));
        SECONDARY.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> resp = chat(NON_STREAM);
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(json.readTree(resp.getBody()).at("/error/code").asText()).isEqualTo("all_providers_unavailable");
        assertThat(jdbc.queryForObject("select count(*) from usage_record where status >= 500", Integer.class)).isEqualTo(2);
    }
}
