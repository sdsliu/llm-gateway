package ru.akiev.gateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Страж «контракт ↔ код» (API first).
 *
 * <p>Сгенерированные интерфейсы уже ловят расхождения на этапе компиляции: если в YAML поменять параметр или
 * тип ответа, контроллер с {@code implements XxxApi} перестанет компилироваться. Но две операции написаны
 * вручную ({@code x-internal: true} — стриминг), и компилятор их не проверяет. Этот тест сравнивает ВСЕ
 * операции контракта (метод + путь) с тем, что реально зарегистрировал Spring MVC, в обе стороны:
 * <ul>
 *   <li>операция есть в YAML, но её никто не обслуживает — тест падает;</li>
 *   <li>в контроллере появился эндпоинт, которого нет в YAML (API «мимо контракта») — тест падает.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContractMappingTest {

    /** Пути, которые не являются API шлюза и в контракт не входят. */
    private static final Set<String> NOT_IN_CONTRACT_PREFIXES = Set.of("/actuator", "/error", "/v3/api-docs",
            "/swagger-ui");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    MockMvc mvc;

    @Test
    void everyContractOperationIsMappedAndNothingElse() {
        Set<String> contract = OpenApiContract.load().operations();

        Set<String> code = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                if (NOT_IN_CONTRACT_PREFIXES.stream().anyMatch(path::startsWith)) {
                    continue;
                }
                info.getMethodsCondition().getMethods().forEach(m -> code.add(m.name() + " " + path));
            }
        });

        assertThat(code).as("эндпоинты контроллеров (метод + путь) должны совпадать с операциями контракта")
                .containsExactlyInAnyOrderElementsOf(contract);
    }

    @Test
    void contractIsServedAndSwaggerUiPointsToIt() throws Exception {
        mvc.perform(get("/openapi/llm-gateway.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.0.3")));
        // /swagger-ui.html — короткий адрес, springdoc перенаправляет на /swagger-ui/index.html
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi/llm-gateway.yaml"));
    }
}
