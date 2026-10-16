package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки одного провайдера (элемент списка gateway.providers).
 *
 * @param priority чем МЕНЬШЕ число, тем выше приоритет (1 — пробуем первым)
 * @param limits   собственные лимиты шлюза (rpm/rpd), 0 или null = без ограничения
 * @param streamUsage слать ли провайдеру stream_options.include_usage в режиме stream
 *                    (Groq, OpenAI, OpenRouter поддерживают; для «самописных» серверов
 *                    можно выключить, если они отвечают 400 на неизвестное поле)
 * @param pingPath    путь для ping дашборда относительно base-url (по умолчанию /models)
 */
public record ProviderProperties(
        @NotBlank String name,
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        @DefaultValue("100") int priority,
        @DefaultValue("120s") Duration timeout,
        @Valid Limits limits,
        @DefaultValue("true") boolean streamUsage,
        @DefaultValue("/models") String pingPath) {

    public record Limits(Integer rpm, Integer rpd) {
    }

    /** Провайдер без ключа пропускаем (например, ключ Z.ai не задан в окружении). */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
