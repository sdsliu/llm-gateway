package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param fallbackModels запасные модели, которые провайдер сам перебирает по порядку, если основная
 *                    недоступна. Отправляется в теле как {@code "models": [...]} — это нативный
 *                    параметр OpenRouter (https://openrouter.ai/docs/guides/routing/model-fallbacks).
 *                    Задавайте только для провайдеров, которые его поддерживают; пусто — не отправляем.
 * @param timeout     таймаут запроса к ЭТОМУ провайдеру (non-stream — весь ответ, stream — пауза до/между
 *                    чанками). Истёк — 504, cooldown и failover на следующего провайдера.
 * @param extraBody   дополнительные поля верхнего уровня в теле /chat/completions, специфичные для
 *                    провайдера (например Z.ai {@code thinking: {type: disabled}}). Поля, которые
 *                    формирует сам шлюз (model, messages, stream, stream_options, models), не переопределяются.
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
        @DefaultValue("/models") String pingPath,
        List<String> fallbackModels,
        Map<String, Object> extraBody) {

    /** Канонический конструктор — по нему Spring Boot биндит application.yml (их у record теперь два). */
    @ConstructorBinding
    public ProviderProperties {
        fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
        extraBody = extraBody == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extraBody));
    }

    /** Без extra-body (для кода и тестов, написанных до его появления). */
    public ProviderProperties(String name, String baseUrl, String apiKey, String model, int priority,
            Duration timeout, Limits limits, boolean streamUsage, String pingPath, List<String> fallbackModels) {
        this(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath, fallbackModels, null);
    }

    public record Limits(Integer rpm, Integer rpd) {
    }

    /** Провайдер без ключа пропускаем (например, ключ Z.ai не задан в окружении). */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
