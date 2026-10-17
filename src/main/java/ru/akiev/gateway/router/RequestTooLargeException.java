package ru.akiev.gateway.router;

/** Запрос больше лимитов всех доступных провайдеров (413 / TPM) — клиент получит 413 request_too_large. */
public class RequestTooLargeException extends RuntimeException {
    public RequestTooLargeException(String message) {
        super(message);
    }
}
