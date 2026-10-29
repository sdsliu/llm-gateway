package ru.akiev.gateway.provider;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.akiev.gateway.dto.ChatRequest;

/**
 * Тело запроса к провайдеру = обычный ChatRequest + провайдер-специфичные поля верхнего уровня:
 * <ul>
 *   <li>{@code "models": [...]} — запасные модели OpenRouter (из {@code fallback-models});
 *       для участника группы моделей список не отправляется;</li>
 *   <li>произвольные поля из {@code extra-body} конфига провайдера, например для Z.ai
 *       {@code "thinking": {"type": "disabled"}}.</li>
 *   <li>для участника группы на OpenRouter — {@code provider.allow_fallbacks: false}
 *       и без {@code route: "fallback"}, чтобы OpenRouter не подменил модель.</li>
 * </ul>
 * Через @JsonUnwrapped поля запроса сериализуются на верхнем уровне, а @JsonAnyGetter дописывает
 * дополнительные — общий DTO и общий код провайдера ничего не знают о специфике конкретного API.
 */
final class ProviderRequestBody {

    /**
     * Поля, которые формирует сам шлюз: extra-body не может их переопределить
     * (иначе конфиг мог бы, например, сломать override модели или флаг stream).
     */
    static final Set<String> RESERVED = Set.of("model", "messages", "stream", "stream_options", "models");

    private final ChatRequest request;
    private final Map<String, Object> extra;

    private ProviderRequestBody(ChatRequest request, Map<String, Object> extra) {
        this.request = request;
        this.extra = extra;
    }

    /** Если добавлять нечего — возвращаем исходный запрос без изменений. */
    static Object of(ChatRequest request, List<String> fallbackModels, Map<String, Object> extraBody) {
        return of(request, fallbackModels, extraBody, false, false);
    }

    /**
     * @param exactModel участник группы: не слать запасной список {@code models}
     * @param disableOpenRouterFallbacks OpenRouter: {@code provider.allow_fallbacks=false} и убрать
     *        {@code route: "fallback"} (иначе OpenRouter отвечает nemotron / laguna / flint вместо
     *        запрошенной модели). Другим провайдерам поле {@code provider} не добавляем.
     */
    static Object of(ChatRequest request, List<String> fallbackModels, Map<String, Object> extraBody,
            boolean exactModel, boolean disableOpenRouterFallbacks) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (extraBody != null) {
            extraBody.forEach((k, v) -> {
                if (!RESERVED.contains(k) && v != null) {
                    extra.put(k, v);
                }
            });
        }
        if (!exactModel && fallbackModels != null && !fallbackModels.isEmpty()) {
            extra.put("models", fallbackModels);
        }
        if (disableOpenRouterFallbacks) {
            pinOpenRouterModel(extra);
        }
        return extra.isEmpty() ? request : new ProviderRequestBody(request, extra);
    }

    /**
     * Запретить OpenRouter подмену модели: ни provider-level fallback, ни model fallback route.
     * Уже заданный {@code provider} (order и т.п.) сохраняем, {@code allow_fallbacks} перезаписываем.
     */
    private static void pinOpenRouterModel(Map<String, Object> extra) {
        Object route = extra.get("route");
        if (route instanceof String s && "fallback".equalsIgnoreCase(s.trim())) {
            extra.remove("route");
        }
        Map<String, Object> provider = new LinkedHashMap<>();
        Object existing = extra.get("provider");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    provider.put(String.valueOf(k), v);
                }
            });
        }
        provider.put("allow_fallbacks", Boolean.FALSE);
        extra.put("provider", provider);
    }

    @JsonUnwrapped
    public ChatRequest getRequest() {
        return request;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }
}
