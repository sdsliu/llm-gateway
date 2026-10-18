package ru.akiev.gateway.limits;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Откуда взялось значение лимита. Порядок приоритета: {@link #HEADERS} &gt; {@link #API} &gt; {@link #CONFIG}.
 * Заголовки — самые свежие (приходят с каждым ответом), API — опрос при старте и по расписанию,
 * конфиг — запасной вариант, если провайдер ничего не сообщил.
 */
public enum LimitSource {
    /** x-ratelimit-limit-* в ответе провайдера (Groq, SambaNova, OpenAI-совместимые). */
    HEADERS,
    /** Специальный эндпоинт провайдера: OpenRouter GET /key, Gonka Broker /test-auth. */
    API,
    /** gateway.providers[].limits в application.yml. */
    CONFIG;

    @JsonValue
    public String json() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
