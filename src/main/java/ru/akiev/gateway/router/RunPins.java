package ru.akiev.gateway.router;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Одна модель на попытку» (gateway.limits.pin-model-per-run): запоминаем, какой провайдер обслужил
 * x-run-id, и следующие запросы этого run отправляем туда же. Записи забываются через pin-ttl после
 * последнего использования. Только в памяти: после рестарта run привяжется заново.
 */
class RunPins {

    private record Pin(String provider, Instant lastUsed) {
    }

    private final Map<String, Pin> pins = new ConcurrentHashMap<>();
    private final Duration ttl;

    RunPins(Duration ttl) {
        this.ttl = ttl;
    }

    /** Провайдер, к которому привязан run (null — не привязан или привязка устарела). */
    String get(String runId, Instant now) {
        if (runId == null) {
            return null;
        }
        Pin p = pins.get(runId);
        if (p == null) {
            return null;
        }
        if (p.lastUsed().plus(ttl).isBefore(now)) {
            pins.remove(runId, p);
            return null;
        }
        return p.provider();
    }

    /** Привязать run к провайдеру; возвращает прежнего провайдера (null — не было). */
    String put(String runId, String provider, Instant now) {
        if (runId == null) {
            return null;
        }
        if (pins.size() > 10_000) {
            pins.values().removeIf(p -> p.lastUsed().plus(ttl).isBefore(now));
        }
        Pin prev = pins.put(runId, new Pin(provider, now));
        return prev == null || prev.lastUsed().plus(ttl).isBefore(now) ? null : prev.provider();
    }
}
