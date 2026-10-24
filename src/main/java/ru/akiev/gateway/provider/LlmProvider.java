package ru.akiev.gateway.provider;

import java.util.List;
import reactor.core.publisher.Flux;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;

/** Абстракция LLM-провайдера. Выбором между провайдерами занимается ProviderRouter. */
public interface LlmProvider {

    String name();

    /** Чем меньше, тем раньше роутер попробует этого провайдера. */
    int priority();

    /** Синхронный вызов chat completions (без стриминга). */
    ChatResponse chat(ChatRequest request);

    /**
     * То же, что {@link #chat(ChatRequest)}, но {@code exactModel} — вызов участника группы моделей:
     * отправить ровно {@code request.model()} и не подключать запасные модели провайдера
     * (у OpenRouter это список {@code models} и provider-level fallback).
     */
    default ChatResponse chat(ChatRequest request, boolean exactModel) {
        return chat(request);
    }

    /**
     * Стриминговый вызов (stream=true). Возвращает Flux «сырых» JSON-строк чанков
     * (то, что идёт после "data: " в SSE провайдера), без финального [DONE].
     * Ошибки HTTP/таймауты приходят как сигнал onError с {@link ProviderException}.
     */
    Flux<String> chatStream(ChatRequest request);

    /** Стрим с тем же смыслом {@code exactModel}, что у {@link #chat(ChatRequest, boolean)}. */
    default Flux<String> chatStream(ChatRequest request, boolean exactModel) {
        return chatStream(request);
    }

    /** Модели, которые провайдер отдаёт в /v1/models. */
    List<String> models();

    /** Runtime-состояние: cooldown, остатки лимитов. */
    ProviderState state();

    /**
     * Сколько запросов может одновременно быть в полёте. {@code null} или неположительное значение — без ограничения.
     * Роутер не выбирает провайдера, пока заняты все слоты.
     */
    default Integer maxConcurrency() {
        return null;
    }
}
