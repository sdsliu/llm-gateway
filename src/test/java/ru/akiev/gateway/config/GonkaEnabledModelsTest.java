package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.akiev.gateway.router.ProviderRouter;

/**
 * С GONKA_API_KEY провайдер включён и его модель видна в /v1/models.
 * Адрес — из GONKA_BASE_URL (локальный, без выхода в сеть). Ключ в лог не пишем.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GonkaEnabledModelsTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("GONKA_API_KEY", () -> "gonka-test-key");
        r.add("GONKA_BASE_URL", () -> "http://127.0.0.1:9/v1");
        r.add("GEMINI_API_KEY", () -> "");
        r.add("gateway.limits.startup-probe", () -> "false");
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:gonka-on;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    ProviderRouter router;
    @Autowired
    GatewayProperties gateway;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void gonkaModelIsListed() throws Exception {
        assertThat(router.names()).contains("gonka").doesNotContain("gemini");
        ProviderProperties gonka = gateway.providers().stream().filter(p -> "gonka".equals(p.name()))
                .findFirst().orElseThrow();
        assertThat(gonka.limitsProbe()).isEqualTo("gonka");
        assertThat(gonka.baseUrl()).isEqualTo("http://127.0.0.1:9/v1");
        assertThat(gonka.enabled()).isTrue();
        JsonNode data = json.readTree(mvc.perform(get("/v1/models")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(data).anySatisfy(model -> {
            assertThat(model.get("id").asText()).isEqualTo(gonka.model());
            assertThat(model.get("owned_by").asText()).isEqualTo("gonka");
        });
        assertThat(data.findValuesAsText("id")).contains("large-context");
    }
}
