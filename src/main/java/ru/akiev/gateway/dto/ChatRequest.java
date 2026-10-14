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
        String user,
        @JsonProperty("stream_options") StreamOptions streamOptions) {

    /** stream_options: {"include_usage": true} — попросить провайдера прислать usage последним чанком. */
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    /** Конструктор без stream_options (для удобства в коде и тестах). */
    public ChatRequest(String model, List<Message> messages, Boolean stream, Double temperature, Double topP,
            Integer maxTokens, List<String> stop, Double presencePenalty, Double frequencyPenalty, String user) {
        this(model, messages, stream, temperature, topP, maxTokens, stop, presencePenalty, frequencyPenalty, user, null);
    }

    /** Не getter-имя (isStream) намеренно: иначе Jackson спутает его с полем stream. */
    public boolean streamRequested() {
        return Boolean.TRUE.equals(stream);
    }

    /** Клиент сам попросил usage в стриме? Тогда usage-чанк ему пересылаем, иначе — только учитываем. */
    public boolean clientWantsStreamUsage() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }

    /** Копия запроса с подменённой моделью (override модели провайдера). */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, stream, temperature, topP, maxTokens, stop,
                presencePenalty, frequencyPenalty, user, streamOptions);
    }

    /** Копия с stream=false (stream_options без stream провайдеры отвергают — убираем). */
    public ChatRequest withoutStream() {
        return new ChatRequest(model, messages, false, temperature, topP, maxTokens, stop,
                presencePenalty, frequencyPenalty, user, null);
    }

    /** Копия с stream=true; includeUsage=true добавляет stream_options.include_usage. */
    public ChatRequest withStream(boolean includeUsage) {
        return new ChatRequest(model, messages, true, temperature, topP, maxTokens, stop,
                presencePenalty, frequencyPenalty, user, includeUsage ? new StreamOptions(true) : streamOptions);
    }
}
