package ru.akiev.gateway.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.provider.mock.MockProvider;
import ru.akiev.gateway.provider.mock.MockScenarioCatalog;
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
     *
     * <p>{@code type: mock} живёт только в профилях {@code mock} и {@code test}. В профиле {@code mock}
     * (и при {@code gateway.mock.exclusive=true}) остальные провайдеры не попадают в роутер, группы моделей
     * не применяются: любое имя модели отвечает mock.
     */
    @Bean
    public ProviderRouter providerRouter(WebClient.Builder builder, GatewayProperties props, Clock clock,
            UsageService usageService, RoutingProperties routing, LimitsProperties limits, Environment environment,
            MockProperties mockProps, ObjectProvider<MockScenarioCatalog> mockCatalog) {
        return buildRouter(builder, props, clock, usageService, routing, limits, environment, mockProps,
                mockCatalog.getIfAvailable());
    }

    /** Для тестов без Spring-окружения: профиль mock не активен, каталога сценариев нет. */
    public ProviderRouter providerRouter(WebClient.Builder builder, GatewayProperties props, Clock clock,
            UsageService usageService, RoutingProperties routing, LimitsProperties limits) {
        return buildRouter(builder, props, clock, usageService, routing, limits, new StandardEnvironment(),
                new MockProperties(null, "diff-ok", false), null);
    }

    private ProviderRouter buildRouter(WebClient.Builder builder, GatewayProperties props, Clock clock,
            UsageService usageService, RoutingProperties routing, LimitsProperties limits, Environment environment,
            MockProperties mockProps, MockScenarioCatalog catalog) {
        boolean mockAllowed = environment.acceptsProfiles(Profiles.of("mock", "test"));
        boolean exclusive = environment.acceptsProfiles(Profiles.of("mock"))
                || (mockProps != null && mockProps.exclusive());
        List<ProviderProperties> usable = new ArrayList<>();
        int skipped = 0;
        for (ProviderProperties p : props.providers()) {
            if ("mock".equals(p.type())) {
                if (!mockAllowed || catalog == null) {
                    log.warn("Провайдер {} отключён: type=mock доступен только в профилях mock и test", p.name());
                    continue;
                }
                String why = p.disabledReason();
                if (why != null) {
                    log.warn("Провайдер {} отключён: {}", p.name(), why);
                    continue;
                }
                usable.add(p);
                continue;
            }
            if (exclusive) {
                skipped++;
                continue;
            }
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
        if (exclusive) {
            log.info("Профиль mock: пропущено провайдеров не-mock: {}, группы моделей не применяются", skipped);
        }
        List<String> names = usable.stream().map(ProviderProperties::name).toList();
        ModelGroups groups = exclusive ? ModelGroups.EMPTY : ModelGroups.compile(props.modelGroups(), names);
        List<LlmProvider> providers = new ArrayList<>();
        for (ProviderProperties p : usable) {
            ProviderProperties ready = p.withExtraModels(groups.extraModels(p.name()));
            if ("mock".equals(ready.type())) {
                providers.add(new MockProvider(ready, clock, java.util.Objects.requireNonNull(catalog), usageService));
            } else {
                providers.add(new OpenAiCompatibleProvider(builder, ready, clock));
            }
        }
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
