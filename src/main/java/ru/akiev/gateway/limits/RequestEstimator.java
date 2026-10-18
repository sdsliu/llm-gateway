package ru.akiev.gateway.limits;

import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.Message;

/**
 * Оценка размера запроса ДО отправки: вход (сообщения) + выход (max_tokens).
 *
 * <p>Точный токенизатор у каждой модели свой, поэтому считаем грубо: ~4 символа на токен плюс
 * ~4 служебных токена на сообщение (роль, разделители). Для лимитов этого достаточно: после ответа
 * оценка заменяется реальным {@code usage} провайдера (см. {@link TokenBudget#settle}).
 * Если клиент не прислал max_tokens, берём {@code gateway.limits.default-max-tokens}.
 */
public final class RequestEstimator {

    static final int CHARS_PER_TOKEN = 4;
    static final int TOKENS_PER_MESSAGE = 4;

    private final int defaultMaxTokens;

    public RequestEstimator(int defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /** input — оценка входа, output — max_tokens (или дефолт), total — их сумма (столько резервируем). */
    public record Estimate(long input, long output) {
        public long total() {
            return input + output;
        }
    }

    public Estimate estimate(ChatRequest request) {
        long chars = 0;
        int messages = 0;
        if (request.messages() != null) {
            for (Message m : request.messages()) {
                messages++;
                chars += m.content() != null ? m.content().length() : 0;
            }
        }
        long input = (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + (long) messages * TOKENS_PER_MESSAGE;
        long output = request.maxTokens() != null && request.maxTokens() > 0 ? request.maxTokens() : defaultMaxTokens;
        return new Estimate(input, output);
    }
}
