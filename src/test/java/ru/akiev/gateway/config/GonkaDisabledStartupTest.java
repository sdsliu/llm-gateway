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

/** Без GONKA_API_KEY шлюз стартует, Gonka выключена и её нет в /v1/models. */
@SpringBootTest
@AutoConfigureMockMvc
class GonkaDisabledStartupTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("GONKA_API_KEY", () -> "");
        r.add("GEMINI_API_KEY", () -> "");
        r.add("gateway.limits.startup-probe", () -> "false");
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:gonka-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    ProviderRouter router;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void gatewayStartsAndGonkaIsAbsentFromModels() throws Exception {
        assertThat(router.names()).doesNotContain("gonka", "gemini");
        assertThat(router.names()).contains("kilo", "ovh");
        JsonNode data = json.readTree(mvc.perform(get("/v1/models")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(data.findValuesAsText("owned_by")).doesNotContain("gonka", "gemini");
        assertThat(data.findValuesAsText("id")).contains("large-context")
                .doesNotContain(GonkaLargeContextConfigTest.GONKA_MODEL);
    }
}
