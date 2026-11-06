package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Gonka в application.yml включается только ключом из окружения, адрес тоже из окружения,
 * группа large-context не содержит Groq и имеет свой TPM.
 */
public class GonkaLargeContextConfigTest {

    static final String PUBLIC_GONKA = "https://proxy.gonkabroker.com/v1";
    static final String GONKA_MODEL = "deepseek-ai/DeepSeek-V4-Flash-0731";

    /** Биндинг application.yml. Пустые ключи перекрывают окружение машины, чтобы тест не зависел от чужих секретов. */
    public static GatewayProperties bind(Map<String, String> overrides) throws Exception {
        var env = new StandardEnvironment();
        Map<String, Object> forced = new LinkedHashMap<>();
        forced.put("GONKA_API_KEY", "");
        forced.put("GEMINI_API_KEY", "");
        forced.put("GROQ_API_KEY", "");
        forced.putAll(overrides);
        env.getPropertySources().addFirst(new MapPropertySource("test-overrides", forced));
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env))
                .bind("gateway", GatewayProperties.class).get();
    }

    @Test
    void yamlKeepsKeysAndEndpointInPlaceholders() throws Exception {
        String raw = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).contains("${GONKA_API_KEY:}");
        assertThat(raw).contains("${GONKA_BASE_URL:" + PUBLIC_GONKA + "}");
        assertThat(raw).contains("${GONKA_LIMITS_PROBE_URL:}");
        assertThat(raw).contains("limits-probe: gonka");
        assertThat(raw).doesNotContain("gnk-prx-");
        assertThat(raw).doesNotContain("AIza");
        assertThat(raw.lines().filter(l -> l.contains("api-key:")).toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("${").contains("}"));
    }

    @Test
    void withoutKeyGonkaAndGeminiAreDisabled() throws Exception {
        GatewayProperties g = bind(Map.of());
        ProviderProperties gonka = ProviderOrderConfigTest.provider(g, "gonka");
        assertThat(gonka.hasApiKey()).isFalse();
        assertThat(gonka.enabled()).isFalse();
        assertThat(gonka.disabledReason()).isEqualTo("api-key не задан");
        assertThat(gonka.limitsProbe()).isEqualTo("gonka");
        String baseFromEnv = System.getenv("GONKA_BASE_URL");
        String expectedBase = baseFromEnv == null || baseFromEnv.isBlank() ? PUBLIC_GONKA : baseFromEnv;
        assertThat(gonka.baseUrl()).isEqualTo(expectedBase);
        assertThat(gonka.limitsProbeUrl()).isNullOrEmpty();
        assertThat(gonka.model()).isEqualTo(GONKA_MODEL);
        assertThat(gonka.limits().rpm()).isEqualTo(6);
        assertThat(gonka.limits().tpm()).isEqualTo(100_000);
        assertThat(gonka.limits().tokensPerMonth()).isEqualTo(1_000_000L);

        ProviderProperties gemini = ProviderOrderConfigTest.provider(g, "gemini");
        assertThat(gemini.enabled()).isFalse();
        assertThat(gemini.disabledReason()).isEqualTo("api-key не задан");
        assertThat(gemini.model()).isEqualTo("gemini-2.5-pro");
        assertThat(gemini.limits().tpm()).isEqualTo(250_000);
        assertThat(ProviderOrderConfigTest.provider(g, "groq").limits().tpm()).isEqualTo(8000);
    }

    @Test
    void keyAloneEnablesGonkaAndBaseUrlComesFromEnvironment() throws Exception {
        GatewayProperties onlyKey = bind(Map.of("GONKA_API_KEY", "gonka-test-key"));
        ProviderProperties gonka = ProviderOrderConfigTest.provider(onlyKey, "gonka");
        assertThat(gonka.enabled()).isTrue();
        assertThat(gonka.usable()).isTrue();
        assertThat(gonka.disabledReason()).isNull();
        assertThat(gonka.limitsProbe()).isEqualTo("gonka");
        String baseFromEnv = System.getenv("GONKA_BASE_URL");
        if (baseFromEnv == null || baseFromEnv.isBlank()) {
            assertThat(gonka.baseUrl()).isEqualTo(PUBLIC_GONKA);
        }

        GatewayProperties custom = bind(Map.of(
                "GONKA_API_KEY", "gonka-test-key",
                "GONKA_BASE_URL", "http://127.0.0.1:9/v1",
                "GONKA_MODEL", "custom/model",
                "GONKA_LIMITS_PROBE_URL", "http://127.0.0.1:9/test-auth"));
        ProviderProperties overridden = ProviderOrderConfigTest.provider(custom, "gonka");
        assertThat(overridden.baseUrl()).isEqualTo("http://127.0.0.1:9/v1");
        assertThat(overridden.model()).isEqualTo("custom/model");
        assertThat(overridden.limitsProbeUrl()).isEqualTo("http://127.0.0.1:9/test-auth");
        assertThat(overridden.limitsProbe()).isEqualTo("gonka");
        ModelGroupProperties group = custom.modelGroups().stream()
                .filter(x -> "large-context".equals(x.name())).findFirst().orElseThrow();
        assertThat(group.members()).filteredOn(m -> "gonka".equals(m.provider()))
                .extracting(ModelGroupProperties.Member::model).containsExactly("custom/model");
    }

    @Test
    void largeContextGroupExcludesGroqAndHasOwnTpm() throws Exception {
        GatewayProperties g = bind(Map.of());
        ModelGroupProperties group = g.modelGroups().stream()
                .filter(x -> "large-context".equals(x.name())).findFirst().orElseThrow();
        assertThat(group.tpmLimit()).isEqualTo(100_000);
        assertThat(group.members()).extracting(ModelGroupProperties.Member::provider)
                .containsExactly("gonka", "gemini", "kilo", "ovh", "openrouter", "sambanova")
                .doesNotContain("groq", "zai", "cloudflare");
        assertThat(group.members()).extracting(ModelGroupProperties.Member::model)
                .containsExactly(GONKA_MODEL, "gemini-2.5-pro", "nvidia/nemotron-3-super-120b-a12b:free",
                        "gpt-oss-120b", "nvidia/nemotron-3-super-120b-a12b:free", "gpt-oss-120b");
        assertThat(30_000).isLessThan(group.tpmLimit());
        assertThat(ProviderOrderConfigTest.provider(g, "groq").limits().tpm()).isLessThan(30_000);

        ModelGroups live = ModelGroups.compile(g.modelGroups(), java.util.List.of("gemini"));
        ModelGroupProperties compiled = live.resolve("large-context").group();
        assertThat(compiled.tpmLimit()).isEqualTo(100_000);
        assertThat(live.providerNames(compiled)).containsExactly("gemini");
    }
}
