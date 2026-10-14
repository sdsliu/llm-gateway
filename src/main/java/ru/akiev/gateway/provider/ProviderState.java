package ru.akiev.gateway.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import ru.akiev.gateway.config.ProviderProperties;

/**
 * Изменяемое состояние провайдера в runtime: cooldown, остатки лимитов из заголовков,
 * счётчики запросов для собственных лимитов rpm/rpd.
 *
 * <p>Методы synchronized: Spring MVC обслуживает запросы в разных потоках одновременно,
 * а состояние у провайдера одно на всех. Для учебного MVP synchronized проще и надёжнее,
 * чем atomic-поля; нагрузка тут — единицы запросов в секунду.
 */
public class ProviderState {

    private final ProviderProperties.Limits limits;
    private Instant cooldownUntil = Instant.EPOCH;
    private String cooldownReason;
    private Long remainingRequests;
    private Long remainingTokens;
    /** Время отправки запросов за последние сутки (для rpm/rpd). */
    private final Deque<Instant> sent = new ArrayDeque<>();

    public ProviderState(ProviderProperties.Limits limits) {
        this.limits = limits;
    }

    /** Можно ли сейчас слать запрос этому провайдеру. */
    public synchronized boolean isAvailable(Instant now) {
        if (now.isBefore(cooldownUntil)) {
            return false;
        }
        prune(now);
        if (limits != null) {
            if (limits.rpd() != null && limits.rpd() > 0 && sent.size() >= limits.rpd()) {
                return false;
            }
            if (limits.rpm() != null && limits.rpm() > 0
                    && countSince(now.minus(Duration.ofMinutes(1))) >= limits.rpm()) {
                return false;
            }
        }
        return true;
    }

    public synchronized void recordRequest(Instant now) {
        sent.addLast(now);
        prune(now);
    }

    public synchronized void cooldown(Instant until, String reason) {
        if (until.isAfter(cooldownUntil)) {
            cooldownUntil = until;
        }
        cooldownReason = reason;
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

    private void prune(Instant now) {
        Instant dayAgo = now.minus(Duration.ofDays(1));
        while (!sent.isEmpty() && sent.peekFirst().isBefore(dayAgo)) {
            sent.pollFirst();
        }
    }

    private long countSince(Instant from) {
        return sent.stream().filter(t -> !t.isBefore(from)).count();
    }
}
