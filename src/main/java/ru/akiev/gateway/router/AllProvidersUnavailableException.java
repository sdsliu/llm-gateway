package ru.akiev.gateway.router;

/** Все провайдеры в cooldown / упали — клиент получит 503. */
public class AllProvidersUnavailableException extends RuntimeException {
    public AllProvidersUnavailableException(String message) {
        super(message);
    }
}
