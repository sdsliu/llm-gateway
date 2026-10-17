package ru.akiev.gateway.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import ru.akiev.gateway.router.RoutingMode;

/**
 * Текущий режим роутинга — ответ GET/PUT /status/routing и поле {@code routing} в snapshot.
 *
 * @param mode      PRIORITY | ROUND_ROBIN
 * @param primary   провайдер, идущий первым в PRIORITY (null — по приоритету)
 * @param providers провайдеры, которые можно выбрать primary (включённые, с api-key), по priority
 * @param order     порядок, в котором следующий запрос будет перебирать провайдеров
 */
public record RoutingInfo(
        RoutingMode mode,
        @JsonInclude(JsonInclude.Include.ALWAYS) String primary,
        List<String> providers,
        List<String> order) {
}
