package ru.akiev.gateway.provider.mock;

import java.time.Duration;
import ru.akiev.gateway.dto.Usage;

/** Один загруженный сценарий mock-провайдера. */
public record MockScenario(
        String id,
        Mode mode,
        String content,
        String firstChunk,
        String body,
        int status,
        Duration retryAfter,
        String message,
        Usage usage) {

    public enum Mode {
        COMPLETION,
        ERROR,
        TIMEOUT,
        STREAM_CUT,
        RAW
    }
}
