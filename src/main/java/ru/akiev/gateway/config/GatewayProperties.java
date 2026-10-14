package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Корневые настройки шлюза (префикс gateway в application.yml).
 * {@code @Validated} включает Bean Validation: если, например, у провайдера не указан
 * base-url, приложение упадёт при старте с понятной ошибкой, а не при первом запросе.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @NotEmpty @Valid List<ProviderProperties> providers,
        @DefaultValue("5m") Duration defaultCooldown) {
}
