package ru.akiev.gateway.provider;

import java.util.List;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;

/** Абстракция LLM-провайдера. На этапе 1 реализация одна, роутинг появится позже. */
public interface LlmProvider {

    String name();

    /** Синхронный вызов chat completions (этап 1, без стриминга). */
    ChatResponse chat(ChatRequest request);

    /** Модели, которые шлюз отдаёт в /v1/models. */
    List<String> models();
}
