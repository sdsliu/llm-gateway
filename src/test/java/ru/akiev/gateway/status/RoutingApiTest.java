package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/** GET/PUT/DELETE /status/routing: валидация, сохранение в роутере и в БД, поле routing в /status. */
@SpringBootTest(properties = {
        "gateway.routing.mode=priority",
        "gateway.routing.primary=",
        "gateway.providers[0].name=alpha", "gateway.providers[0].base-url=http://localhost:1/v1",
        "gateway.providers[0].api-key=k", "gateway.providers[0].model=m-alpha", "gateway.providers[0].priority=1",
        "gateway.providers[1].name=beta", "gateway.providers[1].base-url=http://localhost:1/v1",
        "gateway.providers[1].api-key=", "gateway.providers[1].model=m-beta", "gateway.providers[1].priority=2",
        "gateway.providers[2].name=gamma", "gateway.providers[2].base-url=http://localhost:1/v1",
        "gateway.providers[2].api-key=k", "gateway.providers[2].model=m-gamma", "gateway.providers[2].priority=3"})
@AutoConfigureMockMvc
class RoutingApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ProviderRouter router;
    @Autowired
    RoutingSettingsService settings;
    @Autowired
    RoutingSettingsRepository repository;

    @AfterEach
    void reset() {
        // H2 общая для всех тестовых контекстов — не оставляем сохранённый режим другим тестам.
        settings.reset();
        router.providers().forEach(p -> p.state().resetCooldown());
    }

    ResultActions putRouting(String json) throws Exception {
        return mvc.perform(put("/status/routing").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void getReturnsDefaultsFromConfig() throws Exception {
        mvc.perform(get("/status/routing")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PRIORITY"))
                .andExpect(jsonPath("$.primary").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.providers", Matchers.contains("alpha", "gamma")))
                .andExpect(jsonPath("$.order", Matchers.contains("alpha", "gamma")));
    }

    @Test
    void putPrimaryChangesOrderAndShowsInStatus() throws Exception {
        putRouting("{\"mode\":\"priority\",\"primary\":\"gamma\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PRIORITY"))
                .andExpect(jsonPath("$.primary").value("gamma"))
                .andExpect(jsonPath("$.order", Matchers.contains("gamma", "alpha")));
        org.assertj.core.api.Assertions.assertThat(router.routing())
                .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
        mvc.perform(get("/status")).andExpect(jsonPath("$.routing.primary").value("gamma"));
    }

    @Test
    void putRoundRobinAcceptsDashedName() throws Exception {
        putRouting("{\"mode\":\"round-robin\",\"primary\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$.primary").value(Matchers.nullValue()));
        mvc.perform(get("/status")).andExpect(jsonPath("$.routing.mode").value("ROUND_ROBIN"));
    }

    @Test
    void invalidModeIs400() throws Exception {
        putRouting("{\"mode\":\"random\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", Matchers.containsString("PRIORITY, ROUND_ROBIN")));
        putRouting("{\"primary\":\"alpha\"}").andExpect(status().isBadRequest());
        putRouting("not json").andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(router.routing()).isEqualTo(RoutingSettings.DEFAULT);
    }

    @Test
    void unknownOrDisabledPrimaryIs400() throws Exception {
        putRouting("{\"mode\":\"PRIORITY\",\"primary\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", Matchers.containsString("nope")));
        // beta настроен, но без api-key — выбрать его нельзя
        putRouting("{\"mode\":\"PRIORITY\",\"primary\":\"beta\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", Matchers.containsString("alpha, gamma")));
        org.assertj.core.api.Assertions.assertThat(router.routing()).isEqualTo(RoutingSettings.DEFAULT);
    }

    @Test
    void putIsSavedToDbAndDeleteResetsToConfig() throws Exception {
        putRouting("{\"mode\":\"PRIORITY\",\"primary\":\"gamma\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DB"));
        var saved = repository.findById(RoutingSettingsRecord.SINGLETON_ID).orElseThrow();
        assertThat(saved.getMode()).isEqualTo("PRIORITY");
        assertThat(saved.getPrimaryProvider()).isEqualTo("gamma");
        assertThat(saved.getUpdatedAt()).isNotNull();

        // повторный PUT обновляет ту же единственную строку
        putRouting("{\"mode\":\"round-robin\"}").andExpect(status().isOk());
        assertThat(repository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getMode()).isEqualTo("ROUND_ROBIN"))
                .satisfies(r -> assertThat(r.getPrimaryProvider()).isNull());

        mvc.perform(delete("/status/routing")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PRIORITY"))
                .andExpect(jsonPath("$.primary").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.source").value("CONFIG"))
                .andExpect(jsonPath("$.config.mode").value("PRIORITY"));
        assertThat(repository.count()).isZero();
        assertThat(router.routing()).isEqualTo(RoutingSettings.DEFAULT);
        mvc.perform(get("/status")).andExpect(jsonPath("$.routing.source").value("CONFIG"));
    }

    @Test
    void invalidPutDoesNotTouchDb() throws Exception {
        putRouting("{\"mode\":\"PRIORITY\",\"primary\":\"beta\"}").andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void optionsListAllProvidersAndNoKeyIsDisabled() throws Exception {
        mvc.perform(get("/status/routing")).andExpect(status().isOk())
                .andExpect(jsonPath("$.options[*].name", Matchers.contains("alpha", "beta", "gamma")))
                .andExpect(jsonPath("$.options[0].selectable").value(true))
                .andExpect(jsonPath("$.options[0].state").value("healthy"))
                .andExpect(jsonPath("$.options[1].selectable").value(false))
                .andExpect(jsonPath("$.options[1].state").value("no_key"))
                .andExpect(jsonPath("$.options[2].selectable").value(true));
    }

    @Test
    void primaryInCooldownStaysSelectableAndShowsUntil() throws Exception {
        var gamma = router.providers().stream().filter(p -> p.name().equals("gamma")).findFirst().orElseThrow();
        gamma.state().cooldown(java.time.Instant.now().plusSeconds(600), "HTTP 429");
        mvc.perform(get("/status/routing"))
                .andExpect(jsonPath("$.options[2].state").value("cooldown"))
                .andExpect(jsonPath("$.options[2].selectable").value(true))
                .andExpect(jsonPath("$.options[2].until").isNotEmpty())
                .andExpect(jsonPath("$.options[2].reason").value("HTTP 429"));
        putRouting("{\"mode\":\"PRIORITY\",\"primary\":\"gamma\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value("gamma"))
                .andExpect(jsonPath("$.order", Matchers.contains("gamma", "alpha")));
        assertThat(repository.findById(RoutingSettingsRecord.SINGLETON_ID)).get()
                .extracting(RoutingSettingsRecord::getPrimaryProvider).isEqualTo("gamma");
    }
}
