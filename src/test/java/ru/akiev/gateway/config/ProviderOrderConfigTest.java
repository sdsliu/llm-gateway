package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Порядок groq → zai → kilo → ovh → cloudflare → openrouter → sambanova;
 * Kilo/OVH — keyless (api-key-optional), Cloudflare — по токену и account id;
 * SambaNova: base-url/модель/лимиты Free Tier; Z.ai: thinking выключен через extra-body и короткий таймаут.
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
        // PlaceholdersResolver — как в Spring Boot: ${OVH_AI_RPM:2} -> 2, ${KILO_API_KEY:} -> "" (если не задан)
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env))
                .bind("gateway", GatewayProperties.class).get();
    }

    static ProviderProperties provider(GatewayProperties g, String name) {
        return g.providers().stream().filter(p -> name.equals(p.name())).findFirst().orElseThrow();
    }

    @Test
    void priorityOrder() throws Exception {
        var names = providers().stream()
                .sorted(Comparator.comparingInt(p -> (Integer) p.get("priority")))
                .map(p -> p.get("name")).toList();
        assertThat(names).containsExactly("groq", "zai", "kilo", "ovh", "cloudflare", "openrouter", "sambanova");
    }

    @Test
    void keylessProvidersAfterBinding() throws Exception {
        var g = bound();
        var kilo = provider(g, "kilo");
        assertThat(kilo.baseUrl()).isEqualTo("https://ai-gateway.kilo.ai/api/v1");
        assertThat(kilo.model()).isEqualTo("nvidia/nemotron-3-super-120b-a12b:free");
        assertThat(kilo.apiKeyOptional()).isTrue();
        assertThat(kilo.limits().rpm()).isEqualTo(10);
        assertThat(kilo.limits().rpd()).isEqualTo(4800);

        var ovh = provider(g, "ovh");
        assertThat(ovh.baseUrl()).isEqualTo("https://oai.endpoints.kepler.ai.cloud.ovh.net/v1");
        assertThat(ovh.model()).isEqualTo("gpt-oss-120b");
        assertThat(ovh.apiKeyOptional()).isTrue();
        // анонимно — 2 RPM; с ключом переопределяется через OVH_AI_RPM
        String rpmEnv = System.getenv("OVH_AI_RPM");
        assertThat(ovh.limits().rpm()).isEqualTo(rpmEnv != null ? Integer.parseInt(rpmEnv) : 2);
        assertThat(ovh.limits().rpd()).isNull();

        // Cloudflare и остальные — ключ обязателен
        assertThat(provider(g, "cloudflare").apiKeyOptional()).isFalse();
        assertThat(provider(g, "groq").apiKeyOptional()).isFalse();
        assertThat(provider(g, "openrouter").apiKeyOptional()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rawKeysComeFromEnvironment() throws Exception {
        var byName = providers().stream().collect(java.util.stream.Collectors.toMap(p -> (String) p.get("name"), p -> p));
        assertThat(byName.get("kilo").get("api-key")).isEqualTo("${KILO_API_KEY:}");
        assertThat(byName.get("ovh").get("api-key")).isEqualTo("${OVH_AI_API_KEY:}");
        assertThat(byName.get("cloudflare").get("api-key")).isEqualTo("${CLOUDFLARE_API_TOKEN:}");
        assertThat(byName.get("cloudflare").get("base-url"))
                .isEqualTo("https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID:}/ai/v1");
        assertThat(byName.get("cloudflare").get("model")).isEqualTo("@cf/qwen/qwen3-30b-a3b-fp8");
        assertThat((Map<String, Object>) byName.get("cloudflare").get("limits")).containsEntry("rpm", 300)
                .doesNotContainKey("rpd");
        assertThat(byName.get("cloudflare")).doesNotContainKey("api-key-optional");
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
