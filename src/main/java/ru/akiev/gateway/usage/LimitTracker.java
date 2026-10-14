package ru.akiev.gateway.usage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Счётчики запросов одного провайдера в скользящих окнах: минута (RPM) и сутки (RPD).
 *
 * <p>Храним время каждого запроса за последние 24 часа в очереди (старые — в начале).
 * «Скользящее окно» честнее, чем сброс счётчика в 00:00: лимит «30 в минуту» означает
 * «не больше 30 за любые 60 секунд». Памяти это почти не стоит (максимум rpd меток).
 * Лимиты передаются при создании из gateway.providers[].limits.
 */
public class LimitTracker {

    private final Integer rpm;
    private final Integer rpd;
    private final Deque<Instant> sent = new ArrayDeque<>();

    public LimitTracker(Integer rpm, Integer rpd) {
        this.rpm = rpm;
        this.rpd = rpd;
    }

    public synchronized void increment(Instant now) {
        sent.addLast(now);
        prune(now);
    }

    public synchronized long lastMinute(Instant now) {
        prune(now);
        Instant from = now.minus(Duration.ofMinutes(1));
        return sent.stream().filter(t -> t.isAfter(from)).count();
    }

    public synchronized long lastDay(Instant now) {
        prune(now);
        return sent.size();
    }

    /** true, если ещё один запрос не превысит ни RPM, ни RPD. */
    public synchronized boolean allows(Instant now) {
        if (rpd != null && rpd > 0 && lastDay(now) >= rpd) {
            return false;
        }
        return rpm == null || rpm <= 0 || lastMinute(now) < rpm;
    }

    public Integer rpm() {
        return rpm;
    }

    public Integer rpd() {
        return rpd;
    }

    private void prune(Instant now) {
        Instant dayAgo = now.minus(Duration.ofDays(1));
        while (!sent.isEmpty() && !sent.peekFirst().isAfter(dayAgo)) {
            sent.pollFirst();
        }
    }
}
