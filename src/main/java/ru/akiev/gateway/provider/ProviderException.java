package ru.akiev.gateway.provider;

/** Доменная ошибка вызова провайдера (HTTP 4xx/5xx, таймаут, сеть). */
public class ProviderException extends RuntimeException {
    private final int status;

    public ProviderException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** HTTP-статус, который шлюз вернёт клиенту. */
    public int status() {
        return status;
    }
}
