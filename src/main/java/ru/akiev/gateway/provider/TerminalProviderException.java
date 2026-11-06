package ru.akiev.gateway.provider;

import java.time.Duration;

/**
 * Ошибка провайдера, которую шлюз отдаёт клиенту как есть, без failover.
 * Нужна mock-сценариям fault-injection: 429 с {@code Retry-After}, 503 и таймаут
 * должны дойти до ai-runner, а не превратиться в «все провайдеры недоступны».
 */
public final class TerminalProviderException extends ProviderException {

    public TerminalProviderException(int status, String message, Duration retryAfter) {
        super(status, message, retryAfter, null);
    }

    @Override
    public boolean isRetriable() {
        return false;
    }
}
