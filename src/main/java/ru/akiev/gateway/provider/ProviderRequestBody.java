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
 *   <li>{@code "models": [...]} — запасные модели OpenRouter (из {@code fallback-models});</li>
 *   <li>произвольные поля из {@code extra-body} конфига провайдера, например для Z.ai
 *       {@code "thinking": {"type": "disabled"}}.</li>
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
        Map<String, Object> extra = new LinkedHashMap<>();
        if (extraBody != null) {
            extraBody.forEach((k, v) -> {
                if (!RESERVED.contains(k) && v != null) {
                    extra.put(k, v);
                }
            });
        }
        if (fallbackModels != null && !fallbackModels.isEmpty()) {
            extra.put("models", fallbackModels);
        }
        return extra.isEmpty() ? request : new ProviderRequestBody(request, extra);
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
