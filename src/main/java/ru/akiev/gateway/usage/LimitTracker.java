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
 * Лимиты сначала берутся из gateway.providers[].limits, но могут быть заменены тем, что сообщил сам
 * провайдер ({@link #setLimits}, см. {@code EffectiveLimits}).
 */
public class LimitTracker {

    // volatile: лимиты меняет поток опроса провайдеров, читают потоки запросов.
    private volatile Integer rpm;
    private volatile Integer rpd;
    private final Deque<Instant> sent = new ArrayDeque<>();

    public LimitTracker(Integer rpm, Integer rpd) {
        this.rpm = rpm;
        this.rpd = rpd;
    }

    /** Новые лимиты (обнаруженные у провайдера). null или 0 — без ограничения. */
    public void setLimits(Integer rpm, Integer rpd) {
        this.rpm = rpm;
        this.rpd = rpd;
    }

    /** Восстановить метки запросов после рестарта (из usage_record за последние сутки). */
    public synchronized void restore(java.util.List<Instant> times, Instant now) {
        sent.clear();
        times.stream().sorted().forEach(sent::addLast);
        prune(now);
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

    /**
     * Когда снова можно будет отправить запрос (выйдет из окна самый старый «лишний» запрос),
     * null — если можно уже сейчас. Для дашборда: «лимит исчерпан до HH:MM».
     */
    public synchronized Instant availableAt(Instant now) {
        prune(now);
        Instant at = null;
        if (rpd != null && rpd > 0 && sent.size() >= rpd) {
            at = sent.stream().skip(sent.size() - rpd).findFirst().orElseThrow().plus(Duration.ofDays(1));
        }
        if (rpm != null && rpm > 0) {
            Instant from = now.minus(Duration.ofMinutes(1));
            var minute = sent.stream().filter(t -> t.isAfter(from)).toList();
            if (minute.size() >= rpm) {
                Instant t = minute.get(minute.size() - rpm).plus(Duration.ofMinutes(1));
                at = at == null || t.isAfter(at) ? t : at;
            }
        }
        return at;
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
