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
    public ProviderRouter providerRouter(WebClient.Builder builder, GatewayProperties props, Clock clock) {
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
        return new ProviderRouter(providers, props.defaultCooldown(), clock);
    }
}
