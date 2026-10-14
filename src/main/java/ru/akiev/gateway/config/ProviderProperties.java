package ru.akiev.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки провайдера из блока gateway.provider в application.yml. */
@ConfigurationProperties(prefix = "gateway.provider")
public record ProviderProperties(
        @DefaultValue("groq") String name,
        String baseUrl,
        String apiKey,
        String model,
        @DefaultValue("120s") Duration timeout) {
}
