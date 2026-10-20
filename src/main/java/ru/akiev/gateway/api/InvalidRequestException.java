package ru.akiev.gateway.api;

/**
 * Некорректный запрос клиента → 400 {@code {"error":{"message":..., "type":"invalid_request_error"}}}
 * (см. {@link ApiExceptionHandler}).
 *
 * <p>Нужен из-за API first: методы сгенерированных интерфейсов возвращают строго типизированный
 * {@code ResponseEntity<T>}, и тело ошибки другого типа из них вернуть нельзя. Поэтому контроллер бросает
 * исключение, а формат ответа остаётся прежним — его по-прежнему собирает обработчик ошибок.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
