package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Регрессия: OpenRouter отвечал 404 «No endpoints found», потому что в application.yml была
 * снятая с площадки модель meta-llama/llama-3.3-70b-instruct:free.
 */
class OpenRouterConfigTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> openRouter() throws Exception {
        try (InputStream in = OpenRouterConfigTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            var providers = (List<Map<String, Object>>) ((Map<String, Object>) root.get("gateway")).get("providers");
            return providers.stream().filter(p -> "openrouter".equals(p.get("name"))).findFirst().orElseThrow();
        }
    }

    @Test
    void baseUrlIsApiV1WithoutTrailingSlash() throws Exception {
        assertThat(openRouter().get("base-url")).isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    void modelIsNotRetiredLlamaFree() throws Exception {
        String model = (String) openRouter().get("model");
        assertThat(model).isNotEqualTo("meta-llama/llama-3.3-70b-instruct:free").endsWith(":free");
    }
}
