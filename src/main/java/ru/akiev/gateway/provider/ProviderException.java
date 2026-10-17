package ru.akiev.gateway.provider;

import java.time.Duration;

/** Доменная ошибка вызова провайдера (HTTP 4xx/5xx, таймаут, сеть). */
public class ProviderException extends RuntimeException {
    private final int status;
    private final Duration retryAfter;

    public ProviderException(int status, String message, Throwable cause) {
        this(status, message, null, cause);
    }

    public ProviderException(int status, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /** HTTP-статус, который шлюз вернёт клиенту. */
    public int status() {
        return status;
    }

    /** Значение Retry-After от провайдера, если было. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Есть ли смысл пробовать другого провайдера: 429 (лимит), 5xx (сбой у провайдера),
     * 502/504 (сеть/таймаут). Остальные 4xx (400, 401...) — ошибка запроса или ключа:
     * 400 у другого провайдера, скорее всего, повторится, поэтому отдаём клиенту сразу.
     * 401/403 тоже считаем поводом переключиться — ключ мог протухнуть.
     */
    public boolean isRetriable() {
        return status == 429 || status == 401 || status == 403 || status >= 500 || isNoEndpoints();
    }

    /**
     * OpenRouter 404 «No endpoints found ...»: у провайдера сейчас нет живой модели
     * (free-модель снята, все запасные недоступны, ограничения privacy). У другого провайдера
     * запрос может пройти — переключаемся.
     */
    public boolean isNoEndpoints() {
        return status == 404 && getMessage() != null && getMessage().contains("No endpoints found");
    }
}
