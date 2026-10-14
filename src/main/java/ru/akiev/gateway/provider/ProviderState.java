package ru.akiev.gateway.provider;

import java.time.Duration;
import java.time.Instant;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.usage.LimitTracker;

/**
 * Изменяемое состояние провайдера в runtime: cooldown, остатки лимитов из заголовков,
 * счётчики запросов для собственных лимитов rpm/rpd (делегируем в {@link LimitTracker}).
 *
 * <p>Методы synchronized: Spring MVC обслуживает запросы в разных потоках одновременно,
 * а состояние у провайдера одно на всех. Для учебного MVP synchronized проще и надёжнее,
 * чем atomic-поля; нагрузка тут — единицы запросов в секунду.
 */
public class ProviderState {

    private final LimitTracker limits;
    private Instant cooldownUntil = Instant.EPOCH;
    private String cooldownReason;
    private Long remainingRequests;
    private Long remainingTokens;
    private String lastError;
    private Instant lastErrorAt;

    public ProviderState(ProviderProperties.Limits limits) {
        this.limits = limits == null ? new LimitTracker(null, null) : new LimitTracker(limits.rpm(), limits.rpd());
    }

    /** Можно ли сейчас слать запрос этому провайдеру. */
    public synchronized boolean isAvailable(Instant now) {
        if (now.isBefore(cooldownUntil)) {
            return false;
        }
        return limits.allows(now);
    }

    public synchronized void recordRequest(Instant now) {
        limits.increment(now);
    }

    public synchronized void cooldown(Instant until, String reason) {
        if (until.isAfter(cooldownUntil)) {
            cooldownUntil = until;
        }
        cooldownReason = reason;
    }

    /** Снять cooldown вручную (тесты, будущая админка). */
    public synchronized void resetCooldown() {
        cooldownUntil = Instant.EPOCH;
        cooldownReason = null;
    }

    public synchronized void updateRemaining(Long requests, Long tokens) {
        if (requests != null) {
            remainingRequests = requests;
        }
        if (tokens != null) {
            remainingTokens = tokens;
        }
    }

    public synchronized Instant cooldownUntil() {
        return cooldownUntil;
    }

    public synchronized String cooldownReason() {
        return cooldownReason;
    }

    public synchronized Long remainingRequests() {
        return remainingRequests;
    }

    public synchronized Long remainingTokens() {
        return remainingTokens;
    }

    public LimitTracker limits() {
        return limits;
    }

    /** Что сейчас мешает провайдеру: "cooldown", "limit" или null (здоров). */
    public synchronized String blockedBy(Instant now) {
        if (now.isBefore(cooldownUntil)) {
            return "cooldown";
        }
        return limits.allows(now) ? null : "limit";
    }

    public synchronized void recordError(Instant now, String error) {
        lastError = error;
        lastErrorAt = now;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized Instant lastErrorAt() {
        return lastErrorAt;
    }
}
