package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        @DefaultValue("5m") Duration defaultCooldown,
        @Valid List<ModelGroupProperties> modelGroups,
        /**
         * На сколько выключать участника группы после 404 «model unavailable»
         * (OpenRouter: «This model is unavailable for free», «No endpoints found»).
         * Это пауза именно в этой группе: успешный ping провайдера её не снимает.
         */
        @DefaultValue("10m") Duration modelUnavailableCooldown) {

    @ConstructorBinding
    public GatewayProperties {
        modelGroups = modelGroups == null ? List.of() : List.copyOf(modelGroups);
        if (modelUnavailableCooldown == null || modelUnavailableCooldown.isZero()
                || modelUnavailableCooldown.isNegative()) {
            modelUnavailableCooldown = Duration.ofMinutes(10);
        }
    }

    /** Конструктор для тестов и кода, написанного до групп моделей. */
    public GatewayProperties(List<ProviderProperties> providers, Duration defaultCooldown) {
        this(providers, defaultCooldown, List.of(), Duration.ofMinutes(10));
    }

    /** Без отдельного cooldown «модель недоступна» (10 минут). */
    public GatewayProperties(List<ProviderProperties> providers, Duration defaultCooldown,
            List<ModelGroupProperties> modelGroups) {
        this(providers, defaultCooldown, modelGroups, Duration.ofMinutes(10));
    }
}
