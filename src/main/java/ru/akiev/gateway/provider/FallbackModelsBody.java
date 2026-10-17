package ru.akiev.gateway.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import ru.akiev.gateway.dto.ChatRequest;

/**
 * Тело запроса = обычный ChatRequest + {@code "models": [...]} (запасные модели OpenRouter).
 * Через @JsonUnwrapped поля запроса сериализуются на верхнем уровне, так что общий DTO
 * не знает о специфике OpenRouter.
 */
final class FallbackModelsBody {

    private final ChatRequest request;
    private final List<String> models;

    private FallbackModelsBody(ChatRequest request, List<String> models) {
        this.request = request;
        this.models = models;
    }

    /** Если запасных моделей нет — возвращаем исходный запрос без изменений. */
    static Object of(ChatRequest request, List<String> fallbackModels) {
        return fallbackModels == null || fallbackModels.isEmpty() ? request : new FallbackModelsBody(request, fallbackModels);
    }

    @JsonUnwrapped
    public ChatRequest getRequest() {
        return request;
    }

    @JsonProperty("models")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> getModels() {
        return models;
    }
}
