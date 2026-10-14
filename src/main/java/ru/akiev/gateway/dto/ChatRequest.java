package ru.akiev.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Запрос /v1/chat/completions (подмножество OpenAI API).
 * Неизвестные поля игнорируются, чтобы клиенты (OpenAI SDK, Aider) не получали 400
 * из-за параметров, которые шлюз пока не поддерживает.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        @NotEmpty @Valid List<Message> messages,
        Boolean stream,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<String> stop,
        @JsonProperty("presence_penalty") Double presencePenalty,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        String user) {

    /** Копия запроса с подменённой моделью (override модели провайдера). */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, stream, temperature, topP, maxTokens, stop,
                presencePenalty, frequencyPenalty, user);
    }

    /** Копия запроса с явным stream=false — стриминга на этапе 1 нет. */
    public ChatRequest withoutStream() {
        return new ChatRequest(model, messages, false, temperature, topP, maxTokens, stop,
                presencePenalty, frequencyPenalty, user);
    }
}
