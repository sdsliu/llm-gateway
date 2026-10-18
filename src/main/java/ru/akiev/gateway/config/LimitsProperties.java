package ru.akiev.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки маршрутизации по лимитам (gateway.limits в application.yml, issue #34).
 *
 * @param startupProbe     опрашивать провайдеров при старте (до приёма запросов) и перепинговывать исключённых
 *                         по расписанию. false — не опрашивать вообще: все включённые провайдеры в роутинге,
 *                         лимиты из конфига (так в тестах: сети нет)
 * @param probeTimeout     таймаут опроса ОДНОГО провайдера. Не ответил за это время — исключён из роутинга
 * @param reprobeInterval  как часто перепинговывать исключённых и поставленных на паузу после 429
 * @param refreshInterval  как часто обновлять лимиты из API (OpenRouter /key, Gonka /test-auth) у работающих
 * @param maxWait          сколько запрос может ждать, если места нет ни у кого в эту минуту; потом 429.
 *                         0 — не ждать
 * @param defaultMaxTokens сколько выходных токенов закладывать в оценку, если клиент не прислал max_tokens
 * @param pinModelPerRun   «одна модель на попытку»: все запросы с одним x-run-id идут к одному провайдеру
 *                         (пока он доступен), чтобы агент не прыгал между моделями посреди задачи
 * @param pinTtl           сколько помнить привязку x-run-id → провайдер после последнего запроса
 */
@ConfigurationProperties(prefix = "gateway.limits")
public record LimitsProperties(
        @DefaultValue("true") boolean startupProbe,
        @DefaultValue("PT10S") Duration probeTimeout,
        @DefaultValue("PT1M") Duration reprobeInterval,
        @DefaultValue("PT30M") Duration refreshInterval,
        @DefaultValue("PT20S") Duration maxWait,
        @DefaultValue("1024") int defaultMaxTokens,
        @DefaultValue("false") boolean pinModelPerRun,
        @DefaultValue("PT12H") Duration pinTtl) {

    /** Поведение до #34: без опросов, без ожидания (для конструкторов роутера из старых тестов). */
    public static final LimitsProperties LEGACY = new LimitsProperties(false, Duration.ofSeconds(10),
            Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ZERO, 1024, false, Duration.ofHours(12));

    public LimitsProperties {
        probeTimeout = probeTimeout == null ? Duration.ofSeconds(10) : probeTimeout;
        reprobeInterval = reprobeInterval == null ? Duration.ofMinutes(1) : reprobeInterval;
        refreshInterval = refreshInterval == null ? Duration.ofMinutes(30) : refreshInterval;
        maxWait = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        defaultMaxTokens = defaultMaxTokens <= 0 ? 1024 : defaultMaxTokens;
        pinTtl = pinTtl == null ? Duration.ofHours(12) : pinTtl;
    }
}
