package ru.akiev.gateway.provider;

import java.util.List;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;

/** Абстракция LLM-провайдера. Выбором между провайдерами занимается ProviderRouter. */
public interface LlmProvider {

    String name();

    /** Чем меньше, тем раньше роутер попробует этого провайдера. */
    int priority();

    /** Синхронный вызов chat completions (без стриминга). */
    ChatResponse chat(ChatRequest request);

    /** Модели, которые провайдер отдаёт в /v1/models. */
    List<String> models();

    /** Runtime-состояние: cooldown, остатки лимитов. */
    ProviderState state();
}
