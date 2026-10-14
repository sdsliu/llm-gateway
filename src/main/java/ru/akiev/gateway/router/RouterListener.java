package ru.akiev.gateway.router;

import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;

/**
 * Наблюдатель за попытками роутера. Роутер не знает ни про БД, ни про SSE —
 * он лишь сообщает «попробовали провайдера X, вот результат». Так роутер остаётся простым
 * и тестируемым без Spring.
 */
public interface RouterListener {

    RouterListener NOOP = new RouterListener() {
    };

    default void onSuccess(LlmProvider provider, ChatResponse response, long latencyMs) {
    }

    default void onFailure(LlmProvider provider, ProviderException error, long latencyMs) {
    }
}
