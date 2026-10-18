package ru.akiev.gateway.router;

import java.time.Duration;

/**
 * Места нет ни у одного подходящего провайдера (RPM/RPD/TPM/суточные/месячные лимиты) и за
 * gateway.limits.max-wait не освободилось — клиент получит 429 rate_limit_exceeded с Retry-After.
 */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** Через сколько стоит повторить (оценка по ближайшему освобождению лимита). */
    public Duration retryAfter() {
        return retryAfter;
    }
}
