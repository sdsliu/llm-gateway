package ru.akiev.gateway.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import ru.akiev.gateway.router.RoutingMode;

/**
 * Текущий режим роутинга — ответ GET/PUT/DELETE /status/routing и поле {@code routing} в snapshot.
 *
 * @param mode      PRIORITY | ROUND_ROBIN
 * @param primary   провайдер, идущий первым в PRIORITY (null — по приоритету)
 * @param providers провайдеры, которые можно выбрать primary (включённые: с api-key или keyless), по priority
 * @param order     порядок, в котором следующий запрос будет перебирать провайдеров (недоступные сейчас
 *                  в нём остаются — роутер их пропустит)
 * @param options   все настроенные провайдеры для выпадающего списка primary с живым состоянием
 * @param source    CONFIG — настройки из application.yml, DB — сохранены с дашборда (переживают рестарт)
 * @param warning   сохранённые настройки не удалось применить целиком (например, у primary пропал ключ)
 * @param config    настройки из конфига — к ним вернёт «Сбросить» (DELETE /status/routing)
 */
public record RoutingInfo(
        RoutingMode mode,
        @JsonInclude(JsonInclude.Include.ALWAYS) String primary,
        List<String> providers,
        List<String> order,
        List<ProviderOption> options,
        RoutingSettingsService.Source source,
        String warning,
        ConfigDefault config) {

    /**
     * Провайдер в списке primary.
     *
     * @param selectable можно выбрать primary (false — нет api-key; cooldown/лимит выбор НЕ запрещают)
     * @param state      healthy | cooldown | limit | no_key
     * @param until      до какого момента недоступен (cooldown / окно rpm-rpd), null — доступен
     * @param reason     причина cooldown (например «HTTP 429», «remaining requests = 0»)
     * @param keyless    работает без ключа (api-key-optional)
     */
    public record ProviderOption(
            String name,
            int priority,
            boolean selectable,
            String state,
            Instant until,
            String reason,
            boolean keyless) {
    }

    public record ConfigDefault(
            RoutingMode mode,
            @JsonInclude(JsonInclude.Include.ALWAYS) String primary) {
    }
}
