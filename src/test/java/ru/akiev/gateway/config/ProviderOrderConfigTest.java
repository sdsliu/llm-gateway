package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * SambaNova: base-url/модель/лимиты Free Tier; порядок groq → sambanova → zai → openrouter;
 * Z.ai: thinking выключен через extra-body и короткий таймаут.
 */
class ProviderOrderConfigTest {

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> providers() throws Exception {
        try (InputStream in = ProviderOrderConfigTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            return (List<Map<String, Object>>) ((Map<String, Object>) root.get("gateway")).get("providers");
        }
    }

    /** Биндинг так же, как это делает Spring Boot при старте (relaxed binding, Duration, вложенные Map). */
    static GatewayProperties bound() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addFirst);
        return new Binder(ConfigurationPropertySources.get(env)).bind("gateway", GatewayProperties.class).get();
    }

    static ProviderProperties provider(GatewayProperties g, String name) {
        return g.providers().stream().filter(p -> name.equals(p.name())).findFirst().orElseThrow();
    }

    @Test
    void priorityOrder() throws Exception {
        var names = providers().stream()
                .sorted(Comparator.comparingInt(p -> (Integer) p.get("priority")))
                .map(p -> p.get("name")).toList();
        assertThat(names).containsExactly("groq", "sambanova", "zai", "openrouter");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sambaNovaConfig() throws Exception {
        var sn = providers().stream().filter(p -> "sambanova".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(sn.get("base-url")).isEqualTo("https://api.sambanova.ai/v1");
        assertThat(sn.get("api-key")).isEqualTo("${SAMBANOVA_API_KEY:}");
        assertThat(sn.get("model")).isEqualTo("gpt-oss-120b");
        assertThat((Map<String, Object>) sn.get("limits")).containsEntry("rpm", 20).containsEntry("rpd", 20);
        assertThat(sn).doesNotContainKey("fallback-models").doesNotContainKey("extra-body");
    }

    @Test
    void zaiThinkingDisabledAndShortTimeoutAfterBinding() throws Exception {
        var g = bound();
        var zai = provider(g, "zai");
        assertThat(zai.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(zai.extraBody()).containsOnlyKeys("thinking");
        assertThat(zai.extraBody().get("thinking")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) zai.extraBody().get("thinking")).get("type")).isEqualTo("disabled");
        // остальным провайдерам ничего лишнего в тело не добавляем
        assertThat(provider(g, "groq").extraBody()).isEmpty();
        assertThat(provider(g, "sambanova").extraBody()).isEmpty();
        assertThat(provider(g, "openrouter").extraBody()).isEmpty();
        assertThat(provider(g, "sambanova").timeout()).isEqualTo(Duration.ofSeconds(120));
    }
}
