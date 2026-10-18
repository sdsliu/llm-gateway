package ru.akiev.gateway.limits;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Токены одного провайдера: скользящая минута (TPM), сутки и месяц.
 *
 * <p><b>Минута</b> — очередь «резерваций» (время, токены) за последние 60 секунд, только в памяти.
 * Резервация создаётся ДО отправки (оценка: вход + max_tokens), после ответа уточняется реальным usage.
 * Так два параллельных запроса не «проскочат» в один и тот же остаток TPM.
 *
 * <p><b>Сутки и месяц</b> — суммы токенов за календарные сутки/месяц по UTC. При старте их восстанавливает
 * {@code LimitDiscoveryService} из {@code usage_record} ({@link #restore}), поэтому счётчики переживают перезапуск.
 *
 * <p><b>Заголовки</b>: если провайдер прислал {@code x-ratelimit-remaining-tokens} (Groq: остаток TPM),
 * до момента сброса доступно не больше этого остатка минус то, что мы зарезервировали после ответа.
 *
 * <p>Методы synchronized: состояние одно на все потоки Tomcat, нагрузка — единицы запросов в секунду.
 */
public class TokenBudget {

    static final Duration MINUTE = Duration.ofMinutes(1);

    /** Резервация: время отправки и токены (сначала оценка, после ответа — факт). */
    public static final class Reservation {
        final Instant at;
        long tokens;

        Reservation(Instant at, long tokens) {
            this.at = at;
            this.tokens = tokens;
        }

        public long tokens() {
            return tokens;
        }
    }

    private volatile Long tpm;
    private volatile Long tokensPerDay;
    private volatile Long tokensPerMonth;

    private final Deque<Reservation> minute = new ArrayDeque<>();
    private LocalDate day;
    private long dayTokens;
    private YearMonth month;
    private long monthTokens;
    private Long headerRemaining;
    private Instant headerSeenAt;
    private Instant headerResetAt;

    /** Новые лимиты (при старте, после опроса API или по заголовкам). null — без ограничения. */
    public void setLimits(Long tpm, Long tokensPerDay, Long tokensPerMonth) {
        this.tpm = tpm;
        this.tokensPerDay = tokensPerDay;
        this.tokensPerMonth = tokensPerMonth;
    }

    public Long tpm() {
        return tpm;
    }

    public Long tokensPerDay() {
        return tokensPerDay;
    }

    public Long tokensPerMonth() {
        return tokensPerMonth;
    }

    /** Зарезервировать токены под запрос (вызывается роутером сразу после выбора провайдера). */
    public synchronized Reservation reserve(Instant now, long tokens) {
        roll(now);
        Reservation r = new Reservation(now, tokens);
        minute.addLast(r);
        dayTokens += tokens;
        monthTokens += tokens;
        return r;
    }

    /**
     * Заменить оценку фактом. actual == null — провайдер не прислал usage, оставляем оценку
     * (лучше переоценить, чем упереться в 429).
     */
    public synchronized void settle(Reservation r, Long actual) {
        if (r == null || actual == null || actual < 0) {
            return;
        }
        long delta = actual - r.tokens;
        r.tokens = actual;
        // Сутки/месяц могли смениться между reserve и settle — тогда поправку не переносим в новый период.
        if (day != null && day.equals(LocalDate.ofInstant(r.at, ZoneOffset.UTC))) {
            dayTokens = Math.max(0, dayTokens + delta);
        }
        if (month != null && month.equals(YearMonth.from(r.at.atOffset(ZoneOffset.UTC)))) {
            monthTokens = Math.max(0, monthTokens + delta);
        }
    }

    /** Провайдер сообщил остаток TPM и когда он восстановится (x-ratelimit-remaining/reset-tokens). */
    public synchronized void observeRemaining(Instant now, long remaining, Instant resetAt) {
        headerRemaining = remaining;
        headerSeenAt = now;
        headerResetAt = resetAt != null ? resetAt : now.plus(MINUTE);
    }

    /** Восстановление после рестарта: суммы за сутки и месяц и резервации последней минуты из usage_record. */
    public synchronized void restore(Instant now, long dayTotal, long monthTotal, List<Reservation> lastMinute) {
        day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        month = YearMonth.from(now.atOffset(ZoneOffset.UTC));
        dayTokens = dayTotal;
        monthTokens = monthTotal;
        minute.clear();
        lastMinute.forEach(minute::addLast);
        prune(now);
    }

    public static Reservation past(Instant at, long tokens) {
        return new Reservation(at, tokens);
    }

    public synchronized long minuteTokens(Instant now) {
        prune(now);
        return minute.stream().mapToLong(r -> r.tokens).sum();
    }

    public synchronized long dayTokens(Instant now) {
        roll(now);
        return dayTokens;
    }

    public synchronized long monthTokens(Instant now) {
        roll(now);
        return monthTokens;
    }

    /**
     * Сколько токенов можно потратить прямо сейчас в пределах TPM (с учётом заголовков). null — TPM не ограничен.
     */
    public synchronized Long minuteRemaining(Instant now) {
        prune(now);
        Long limit = tpm;
        Long avail = limit == null ? null : limit - minute.stream().mapToLong(r -> r.tokens).sum();
        if (headerRemaining != null && now.isBefore(headerResetAt)) {
            long since = minute.stream().filter(r -> r.at.isAfter(headerSeenAt)).mapToLong(r -> r.tokens).sum();
            long h = headerRemaining - since;
            avail = avail == null ? h : Math.min(avail, h);
        }
        return avail;
    }

    /**
     * Проверка «влезет ли запрос на tokens токенов».
     *
     * @return null — влезает; иначе {@link Block} с причиной и моментом, когда может влезть (null — не в этом
     *         окне ожидания: суточный/месячный лимит или запрос больше самого TPM)
     */
    public synchronized Block check(Instant now, long tokens) {
        roll(now);
        Long tpmLimit = tpm;
        if (tpmLimit != null && tokens > tpmLimit) {
            return new Block("tpm", null, true);
        }
        Long avail = minuteRemaining(now);
        if (avail != null && tokens > avail) {
            return new Block("tpm", minuteFreesAt(now, tokens, avail), false);
        }
        if (tokensPerDay != null && dayTokens + tokens > tokensPerDay) {
            return new Block("tokens_per_day", day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), false);
        }
        if (tokensPerMonth != null && monthTokens + tokens > tokensPerMonth) {
            return new Block("tokens_per_month", month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                    false);
        }
        return null;
    }

    /**
     * Доля запаса по токенным лимитам после этого запроса: min по TPM/суткам/месяцу (remaining - tokens) / limit.
     * 1.0 — лимитов нет (или запас полный).
     */
    public synchronized double headroom(Instant now, long tokens) {
        roll(now);
        double h = 1.0;
        Long avail = minuteRemaining(now);
        if (tpm != null && avail != null) {
            h = Math.min(h, (double) (avail - tokens) / tpm);
        }
        if (tokensPerDay != null) {
            h = Math.min(h, (double) (tokensPerDay - dayTokens - tokens) / tokensPerDay);
        }
        if (tokensPerMonth != null) {
            h = Math.min(h, (double) (tokensPerMonth - monthTokens - tokens) / tokensPerMonth);
        }
        return h;
    }

    /**
     * Почему не влезает.
     *
     * @param reason     tpm | tokens_per_day | tokens_per_month
     * @param retryAt    когда освободится достаточно (null — неизвестно)
     * @param neverFits  запрос больше самого лимита TPM — ждать бесполезно
     */
    public record Block(String reason, Instant retryAt, boolean neverFits) {
    }

    /**
     * Когда влезут tokens: собственное окно освобождается по мере выхода старых резерваций; если действует
     * остаток из заголовков — не раньше его сброса (после сброса остаётся только своё окно).
     */
    private Instant minuteFreesAt(Instant now, long tokens, long avail) {
        Instant at = null;
        if (tpm != null) {
            long free = tpm - minute.stream().mapToLong(r -> r.tokens).sum();
            for (Reservation r : minute) {
                if (free >= tokens) {
                    break;
                }
                free += r.tokens;
                at = r.at.plus(MINUTE);
            }
        }
        if (headerRemaining != null && now.isBefore(headerResetAt)) {
            at = at == null || headerResetAt.isAfter(at) ? headerResetAt : at;
        }
        return at;
    }

    /** Сменились сутки/месяц (UTC) — обнуляем соответствующий счётчик. */
    private void roll(Instant now) {
        LocalDate d = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!d.equals(day)) {
            day = d;
            dayTokens = 0;
        }
        YearMonth m = YearMonth.from(now.atOffset(ZoneOffset.UTC));
        if (!m.equals(month)) {
            month = m;
            monthTokens = 0;
        }
        prune(now);
    }

    private void prune(Instant now) {
        Instant from = now.minus(MINUTE);
        while (!minute.isEmpty() && !minute.peekFirst().at.isAfter(from)) {
            minute.pollFirst();
        }
        if (headerResetAt != null && !now.isBefore(headerResetAt)) {
            headerRemaining = null;
        }
    }
}
