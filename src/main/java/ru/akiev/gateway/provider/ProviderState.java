package ru.akiev.gateway.provider;

import java.time.Duration;
import java.time.Instant;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.limits.DiscoveredLimits;
import ru.akiev.gateway.limits.EffectiveLimits;
import ru.akiev.gateway.limits.TokenBudget;
import ru.akiev.gateway.usage.LimitTracker;

/**
 * Изменяемое состояние провайдера в runtime: cooldown, остатки лимитов из заголовков,
 * счётчики запросов для лимитов rpm/rpd (делегируем в {@link LimitTracker}) и токенов
 * tpm / tokens-per-day / tokens-per-month (в {@link TokenBudget}).
 *
 * <p>Лимиты бывают из трёх источников (конфиг, API провайдера, заголовки ответа) — действующие
 * считает {@link EffectiveLimits#resolve} и раздаёт трекерам при каждом изменении.
 *
 * <p>Провайдер может быть <b>исключён</b> из роутинга ({@link #exclude}): не ответил на ping при старте
 * или ответил 429. Исключённого периодически пингует {@code LimitDiscoveryService} и возвращает
 * ({@link #include}), как только он ответит.
 *
 * <p>Методы synchronized: Spring MVC обслуживает запросы в разных потоках одновременно,
 * а состояние у провайдера одно на всех. Для учебного MVP synchronized проще и надёжнее,
 * чем atomic-поля; нагрузка тут — единицы запросов в секунду.
 */
public class ProviderState {

    private final LimitTracker limits;
    private final TokenBudget budget = new TokenBudget();
    private final DiscoveredLimits configLimits;
    private DiscoveredLimits apiLimits = DiscoveredLimits.NONE;
    private DiscoveredLimits headerLimits = DiscoveredLimits.NONE;
    private volatile EffectiveLimits effective;
    private boolean excluded;
    private String excludedReason;
    private Instant excludedSince;
    private Instant pausedBy429At;
    private Instant cooldownUntil = Instant.EPOCH;
    private String cooldownReason;
    private Long remainingRequests;
    private Long remainingTokens;
    private Instant resetRequestsAt;
    private Instant resetTokensAt;
    private Instant limitsSeenAt;
    private String lastError;
    private Instant lastErrorAt;

    public ProviderState(ProviderProperties.Limits limits) {
        this.limits = limits == null ? new LimitTracker(null, null) : new LimitTracker(limits.rpm(), limits.rpd());
        this.configLimits = DiscoveredLimits.fromConfig(limits);
        recompute();
    }

    // ---------- лимиты: конфиг / API / заголовки ----------

    /** Лимиты из API провайдера (опрос при старте и по расписанию). Возвращает true, если действующие изменились. */
    public synchronized boolean applyApiLimits(DiscoveredLimits api) {
        apiLimits = api == null ? DiscoveredLimits.NONE : api;
        return recompute();
    }

    /** Лимиты из заголовков x-ratelimit-limit-* (накапливаются: новое поле не стирает старые). */
    public synchronized boolean applyHeaderLimits(DiscoveredLimits headers) {
        if (headers == null || !headers.any()) {
            return false;
        }
        headerLimits = headerLimits.overlay(headers);
        return recompute();
    }

    /** Действующие лимиты с источником по каждому полю. */
    public EffectiveLimits effectiveLimits() {
        return effective;
    }

    private boolean recompute() {
        EffectiveLimits next = EffectiveLimits.resolve(configLimits, apiLimits, headerLimits);
        boolean changed = !next.equals(effective);
        effective = next;
        limits.setLimits(toInt(next.rpm().value()), toInt(next.rpd().value()));
        budget.setLimits(next.tpm().value(), next.tokensPerDay().value(), next.tokensPerMonth().value());
        return changed;
    }

    private static Integer toInt(Long v) {
        return v == null ? null : (int) Math.min(Integer.MAX_VALUE, v);
    }

    public TokenBudget budget() {
        return budget;
    }

    // ---------- исключение из роутинга ----------

    /** Убрать провайдера из роутинга (не ответил на ping / 429). Его будет перепинговывать LimitDiscoveryService. */
    public synchronized void exclude(Instant now, String reason) {
        if (!excluded) {
            excludedSince = now;
        }
        excluded = true;
        excludedReason = reason;
    }

    /** Вернуть в роутинг: снять исключение, паузу после 429 и cooldown. */
    public synchronized void include() {
        excluded = false;
        excludedReason = null;
        excludedSince = null;
        pausedBy429At = null;
        cooldownUntil = Instant.EPOCH;
        cooldownReason = null;
    }

    public synchronized boolean excluded() {
        return excluded;
    }

    public synchronized String excludedReason() {
        return excludedReason;
    }

    public synchronized Instant excludedSince() {
        return excludedSince;
    }

    /**
     * Пауза после 429: обычный cooldown (до Retry-After или default-cooldown) плюс отметка, что пауза
     * «от 429» — такую LimitDiscoveryService снимает досрочно после первого успешного ping.
     */
    public synchronized void pauseAfter429(Instant now, Instant until, String reason) {
        cooldown(until, reason);
        pausedBy429At = now;
    }

    /** Когда началась текущая пауза от 429 (null — паузы нет или она уже кончилась). */
    public synchronized Instant pausedBy429At(Instant now) {
        if (pausedBy429At != null && !now.isBefore(cooldownUntil)) {
            pausedBy429At = null;
        }
        return pausedBy429At;
    }

    // ---------- можно ли слать ----------

    /**
     * Сколько места у провайдера под запрос на tokens токенов.
     *
     * @param fits     влезает прямо сейчас
     * @param headroom доля запаса после запроса: min по всем известным лимитам (remaining - запрос) / limit;
     *                 1.0 — лимитов нет. Больше — свободнее (режим HEADROOM выбирает максимум)
     * @param blockedBy null, excluded, cooldown, limit (rpm/rpd), tpm, tokens_per_day, tokens_per_month
     * @param retryAt  когда может освободиться (для ожидания); null — неизвестно / не скоро
     * @param neverFits запрос больше TPM провайдера — ждать бессмысленно
     */
    public record Capacity(boolean fits, double headroom, String blockedBy, Instant retryAt, boolean neverFits) {
    }

    public synchronized Capacity capacity(Instant now, long tokens) {
        if (excluded) {
            return new Capacity(false, -1, "excluded", null, false);
        }
        TokenBudget.Block tooBig = budget.check(now, tokens);
        if (tooBig != null && tooBig.neverFits()) {
            return new Capacity(false, -1, "tpm", null, true);
        }
        if (now.isBefore(cooldownUntil)) {
            return new Capacity(false, -1, "cooldown", cooldownUntil, false);
        }
        if (!limits.allows(now)) {
            return new Capacity(false, -1, "limit", limits.availableAt(now), false);
        }
        if (tooBig != null) {
            return new Capacity(false, -1, tooBig.reason(), tooBig.retryAt(), false);
        }
        double h = budget.headroom(now, tokens);
        Integer rpm = limits.rpm();
        if (rpm != null && rpm > 0) {
            h = Math.min(h, (double) (rpm - limits.lastMinute(now) - 1) / rpm);
        }
        Integer rpd = limits.rpd();
        if (rpd != null && rpd > 0) {
            h = Math.min(h, (double) (rpd - limits.lastDay(now) - 1) / rpd);
        }
        return new Capacity(true, h, null, null, false);
    }

    /** Можно ли сейчас слать запрос этому провайдеру (без учёта токенов конкретного запроса). */
    public synchronized boolean isAvailable(Instant now) {
        if (excluded || now.isBefore(cooldownUntil)) {
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

    /** Снять cooldown вручную (тесты, будущая админка) или после успешного ping. */
    public synchronized void resetCooldown() {
        cooldownUntil = Instant.EPOCH;
        cooldownReason = null;
        pausedBy429At = null;
    }

    public synchronized void updateRemaining(Long requests, Long tokens) {
        if (requests != null) {
            remainingRequests = requests;
        }
        if (tokens != null) {
            remainingTokens = tokens;
        }
    }

    /** Запомнить, когда провайдер обещал сбросить лимиты (из x-ratelimit-reset-*), и время ответа. */
    public synchronized void updateResets(Instant now, Instant requestsAt, Instant tokensAt) {
        limitsSeenAt = now;
        if (requestsAt != null) {
            resetRequestsAt = requestsAt;
        }
        if (tokensAt != null) {
            resetTokensAt = tokensAt;
        }
    }

    public synchronized Instant resetRequestsAt() {
        return resetRequestsAt;
    }

    public synchronized Instant resetTokensAt() {
        return resetTokensAt;
    }

    /** Когда последний раз пришли заголовки x-ratelimit-* (null — провайдер их не присылал). */
    public synchronized Instant limitsSeenAt() {
        return limitsSeenAt;
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

    /** Что сейчас мешает провайдеру: "excluded", "cooldown", "limit" или null (здоров). */
    public synchronized String blockedBy(Instant now) {
        if (excluded) {
            return "excluded";
        }
        if (now.isBefore(cooldownUntil)) {
            return "cooldown";
        }
        if (!limits.allows(now)) {
            return "limit";
        }
        TokenBudget.Block b = budget.check(now, 1);
        return b == null ? null : "limit";
    }

    /** До какого момента провайдер недоступен (конец cooldown или окна лимита), null — доступен сейчас. */
    public synchronized Instant blockedUntil(Instant now) {
        if (now.isBefore(cooldownUntil)) {
            return cooldownUntil;
        }
        Instant at = limits.availableAt(now);
        if (at == null) {
            TokenBudget.Block b = budget.check(now, 1);
            at = b == null ? null : b.retryAt();
        }
        return at;
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
