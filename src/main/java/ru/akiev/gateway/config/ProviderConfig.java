package ru.akiev.gateway.config;

import java.time.Clock;
import java.util.ArrayList;
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
     * Создаём по провайдеру на каждый элемент gateway.providers, у которого задан ключ
     * (или ключ необязателен: api-key-optional: true) и корректный base-url.
     * Пустой ключ, битый URL (пустой сегмент пути — не задан account id) — провайдер отключается с WARN.
     * WebClient.Builder Spring Boot создаёт сам (с настроенным Jackson), провайдер делает clone().
     */
    @Bean
    public ProviderRouter providerRouter(WebClient.Builder builder, GatewayProperties props, Clock clock,
            UsageService usageService, RoutingProperties routing, LimitsProperties limits) {
        List<ProviderProperties> usable = new ArrayList<>();
        for (ProviderProperties p : props.providers()) {
            String why = p.disabledReason();
            if (why != null) {
                log.warn("Провайдер {} отключён: {}", p.name(), why);
                continue;
            }
            if (!p.hasApiKey()) {
                log.info("Провайдер {}: api-key не задан, работаем без ключа (keyless)", p.name());
            }
            usable.add(p);
        }
        ModelGroups groups = ModelGroups.compile(props.modelGroups(),
                usable.stream().map(ProviderProperties::name).toList());
        List<LlmProvider> providers = usable.stream()
                .map(p -> p.withExtraModels(groups.extraModels(p.name())))
                .map(p -> (LlmProvider) new OpenAiCompatibleProvider(builder, p, clock))
                .toList();
        if (providers.isEmpty()) {
            log.warn("Нет ни одного включённого провайдера — все запросы будут получать 503");
        }
        ProviderRouter router = new ProviderRouter(providers, props.defaultCooldown(), clock, usageService,
                limits, ProviderRouter.Sleeper.THREAD, groups, props.modelUnavailableCooldown());
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
        ProviderProperties configured = props.providers().stream()
                .filter(p -> p.name().equals(s.primary())).findFirst().orElse(null);
        if (configured == null) {
            throw new IllegalStateException("gateway.routing.primary=" + s.primary()
                    + ": такого провайдера нет в gateway.providers");
        }
        String why = configured.disabledReason();
        log.warn("gateway.routing.primary={} пропущен: {}", s.primary(),
                why != null ? why : "провайдер не включён");
        return new RoutingSettings(s.mode(), null);
    }
}
