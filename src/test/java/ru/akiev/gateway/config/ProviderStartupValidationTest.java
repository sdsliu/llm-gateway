package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageService;

/** Битый URL и пустой ключ выключают провайдера при старте, с предупреждением в логе. */
class ProviderStartupValidationTest {

    static ProviderProperties provider(String name, String url, String key, boolean keyOptional) {
        return new ProviderProperties(name, url, key, "m", 1, Duration.ofSeconds(5), null, true, "/models", null,
                null, keyOptional);
    }

    @Test
    void emptyAccountIdAndMissingKeyAreDisabled() {
        var cloudflare = new ProviderProperties("cloudflare",
                "https://api.cloudflare.com/client/v4/accounts//ai/v1",
                "token", "@cf/qwen/qwen3-30b-a3b-fp8", 5, Duration.ofSeconds(5), null, true, "/models", null);
        assertThat(cloudflare.enabled()).isTrue();
        assertThat(cloudflare.disabledReason()).contains("пустым сегментом").contains("accounts//ai");
        assertThat(provider("groq", "https://api.groq.com/openai/v1", "", false).disabledReason())
                .isEqualTo("api-key не задан");
        assertThat(provider("groq", "https://api.groq.com/openai/v1", "k", false).disabledReason()).isNull();
        assertThat(provider("kilo", "https://ai-gateway.kilo.ai/api/v1", "", true).usable()).isTrue();
        assertThat(new ProviderProperties("x", "https://example.com/${NOT_SET}/v1", "k", "m", 1,
                Duration.ofSeconds(5), null, true, "/models", null).disabledReason()).contains("placeholder");

        var ovh = provider("ovh", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "", true);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderConfig.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var gp = new GatewayProperties(List.of(cloudflare, ovh,
                    provider("groq", "https://api.groq.com/openai/v1", "  ", false)), Duration.ofMinutes(5));
            ProviderRouter router = new ProviderConfig().providerRouter(WebClient.builder(), gp, Clock.systemUTC(),
                    Mockito.mock(UsageService.class), null, LimitsProperties.LEGACY);
            assertThat(router.names()).containsExactly("ovh");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("cloudflare").contains("отключён").contains("пустым сегментом");
        });
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("groq").contains("отключён").contains("api-key не задан"));
    }

    @Test
    void applicationYmlBindsGptOssGroup() throws Exception {
        GatewayProperties g = ProviderOrderConfigTest.bound();
        ModelGroupProperties group = g.modelGroups().stream()
                .filter(x -> "gpt-oss-120b".equals(x.name())).findFirst().orElseThrow();
        assertThat(group.aliases()).containsExactly("openai/gpt-oss-120b");
        assertThat(group.members()).extracting(ModelGroupProperties.Member::provider)
                .containsExactly("groq", "ovh", "sambanova", "openrouter");
        assertThat(group.members()).extracting(ModelGroupProperties.Member::model)
                .containsExactly("openai/gpt-oss-120b", "gpt-oss-120b", "gpt-oss-120b", "openai/gpt-oss-120b");
        assertThat(g.modelUnavailableCooldown()).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(ProviderOrderConfigTest.provider(g, "ovh").maxConcurrency()).isEqualTo(2);
        assertThat(ProviderOrderConfigTest.provider(g, "groq").maxConcurrency()).isNull();
    }
}
