package ru.akiev.gateway.config;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingSettings;
import ru.akiev.gateway.usage.UsageService;

@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    /** Часы выносим в бин, чтобы в тестах подменять время (проверка cooldown). */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Создаём по провайдеру на каждый элемент gateway.providers, у которого задан ключ.
     * WebClient.Builder Spring Boot создаёт сам (с настроенным Jackson), провайдер делает clone().
     */
    @Bean
    public ProviderRouter providerRouter(WebClient.Builder builder, GatewayProperties props, Clock clock,
            UsageService usageService, RoutingProperties routing) {
        List<LlmProvider> providers = props.providers().stream()
                .filter(p -> {
                    if (!p.hasApiKey()) {
                        log.warn("Провайдер {} пропущен: api-key пустой", p.name());
                    }
                    return p.hasApiKey();
                })
                .map(p -> (LlmProvider) new OpenAiCompatibleProvider(builder, p, clock))
                .toList();
        if (providers.isEmpty()) {
            log.warn("Нет ни одного провайдера с ключом — все запросы будут получать 503");
        }
        ProviderRouter router = new ProviderRouter(providers, props.defaultCooldown(), clock, usageService);
        router.setRouting(initialRouting(props, routing, router));
        log.info("Режим роутинга при старте: {}", router.routing());
        return router;
    }

    /**
     * gateway.routing из конфига. Опечатка в primary (такого провайдера нет вообще) — ошибка старта;
     * провайдер есть, но без api-key — предупреждение, работаем по чистому приоритету.
     */
    static RoutingSettings initialRouting(GatewayProperties props, RoutingProperties routing, ProviderRouter router) {
        RoutingSettings s = new RoutingSettings(routing != null ? routing.mode() : null,
                routing != null ? routing.primary() : null);
        if (s.primary() == null || router.names().contains(s.primary())) {
            return s;
        }
        boolean configured = props.providers().stream().anyMatch(p -> p.name().equals(s.primary()));
        if (!configured) {
            throw new IllegalStateException("gateway.routing.primary=" + s.primary()
                    + ": такого провайдера нет в gateway.providers");
        }
        log.warn("gateway.routing.primary={} пропущен: у провайдера нет api-key", s.primary());
        return new RoutingSettings(s.mode(), null);
    }
}
