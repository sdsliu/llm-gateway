package ru.akiev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/** Привязка gateway.routing и стартовая проверка primary. */
class RoutingConfigTest {

    @Configuration
    @EnableConfigurationProperties(RoutingProperties.class)
    static class Cfg {
    }

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Cfg.class);

    @Test
    void defaultsToPriorityWithoutPrimary() {
        runner.run(ctx -> {
            RoutingProperties p = ctx.getBean(RoutingProperties.class);
            assertThat(p.mode()).isEqualTo(RoutingMode.PRIORITY);
            assertThat(p.primary()).isNull();
        });
    }

    @Test
    void bindsRoundRobinAndPrimary() {
        runner.withPropertyValues("gateway.routing.mode=round-robin", "gateway.routing.primary=groq").run(ctx -> {
            RoutingProperties p = ctx.getBean(RoutingProperties.class);
            assertThat(p.mode()).isEqualTo(RoutingMode.ROUND_ROBIN);
            assertThat(p.primary()).isEqualTo("groq");
        });
    }

    ProviderProperties pp(String name, String key, int prio) {
        return new ProviderProperties(name, "http://localhost:1/v1", key, "m", prio, Duration.ofSeconds(5), null, true,
                "/models", null);
    }

    @Test
    void startupPrimaryValidation() {
        var props = new GatewayProperties(List.of(pp("groq", "k", 1), pp("zai", "", 2)), Duration.ofMinutes(5));
        var router = new ProviderRouter(List.<LlmProvider>of(
                new OpenAiCompatibleProvider(WebClient.builder(), pp("groq", "k", 1), Clock.systemUTC())),
                Duration.ofMinutes(5), Clock.systemUTC());

        assertThat(ProviderConfig.initialRouting(props, new RoutingProperties(RoutingMode.PRIORITY, "groq"), router))
                .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "groq"));
        // настроен, но без ключа — игнорируем с предупреждением
        assertThat(ProviderConfig.initialRouting(props, new RoutingProperties(RoutingMode.ROUND_ROBIN, "zai"), router))
                .isEqualTo(new RoutingSettings(RoutingMode.ROUND_ROBIN, null));
        // опечатка — ошибка старта
        assertThatThrownBy(() -> ProviderConfig.initialRouting(props, new RoutingProperties(RoutingMode.PRIORITY, "gorq"), router))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gorq");
    }
}
