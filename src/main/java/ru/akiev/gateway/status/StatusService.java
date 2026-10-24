package ru.akiev.gateway.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.status.StatusSnapshot.HourBucket;
import ru.akiev.gateway.status.StatusSnapshot.ProviderStatus;
import ru.akiev.gateway.status.StatusSnapshot.RecentRequest;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Собирает {@link StatusSnapshot}: runtime-состояние провайдеров (cooldown, остатки из заголовков,
 * RPM/RPD из LimitTracker) + агрегаты из usage_record.
 *
 * <p>Агрегируем в Java по записям за последние 24 часа — так не нужен SQL, специфичный для
 * PostgreSQL (date_trunc), и тот же код работает с H2 в тестах. Для личного шлюза это сотни
 * или тысячи строк — дёшево. При росте нагрузки перенесём группировку в SQL.
 */
@Service
public class StatusService {

    private final ProviderRouter router;
    private final GatewayProperties props;
    private final UsageRecordRepository repository;
    private final OpenRouterKeyPoller openRouter;
    private final ProviderHealthService health;
    private final Clock clock;
    private final RoutingSettingsService routingSettings;
    private final ru.akiev.gateway.limits.LimitDiscoveryService discovery;
    private final ZoneId zone = ZoneId.systemDefault();

    public StatusService(ProviderRouter router, GatewayProperties props, UsageRecordRepository repository,
            OpenRouterKeyPoller openRouter, ProviderHealthService health, Clock clock,
            RoutingSettingsService routingSettings, ru.akiev.gateway.limits.LimitDiscoveryService discovery) {
        this.routingSettings = routingSettings;
        this.discovery = discovery;
        this.health = health;
        this.router = router;
        this.props = props;
        this.repository = repository;
        this.openRouter = openRouter;
        this.clock = clock;
    }

    /** Текущее «состояние» каждого включённого провайдера — для отслеживания изменений. */
    public Map<String, String> providerStates() {
        Instant now = clock.instant();
        Map<String, String> m = new LinkedHashMap<>();
        for (LlmProvider p : router.providers()) {
            String b = p.state().blockedBy(now);
            m.put(p.name(), b == null ? "healthy" : b);
        }
        return m;
    }

    /** Провайдер включён, но api-key не задан — значит, работает в keyless-режиме (api-key-optional). */
    private boolean keyless(String name) {
        return props.providers().stream().anyMatch(pp -> pp.name().equals(name) && !pp.hasApiKey());
    }

    public StatusSnapshot snapshot() {
        Instant now = clock.instant();
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Instant startOfDay = now.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant();
        List<UsageRecord> last24h = repository.findByCreatedAtAfterOrderByCreatedAtAsc(dayAgo);
        List<UsageRecord> today = last24h.stream().filter(r -> !r.getCreatedAt().isBefore(startOfDay)).toList();

        List<ProviderStatus> providers = new ArrayList<>();
        Set<String> enabled = router.providers().stream().map(LlmProvider::name).collect(Collectors.toSet());
        for (LlmProvider p : router.providers()) {
            ProviderState s = p.state();
            List<UsageRecord> mine = today.stream().filter(r -> r.getProvider().equals(p.name())).toList();
            String blocked = s.blockedBy(now);
            providers.add(new ProviderStatus(
                    p.name(), p.models().stream().findFirst().orElse(null), p.priority(),
                    blocked == null ? "healthy" : blocked,
                    "cooldown".equals(blocked) ? s.cooldownUntil() : null,
                    "cooldown".equals(blocked) ? s.cooldownReason() : null,
                    s.remainingRequests(), s.remainingTokens(),
                    s.limits().lastMinute(now), s.limits().rpm(),
                    s.limits().lastDay(now), s.limits().rpd(),
                    mine.size(), tokens(mine), mine.stream().filter(r -> !r.isSuccess()).count(),
                    mine.isEmpty() ? null : Math.round(mine.stream().mapToLong(UsageRecord::getLatencyMs).average().orElse(0)),
                    s.lastError(), s.lastErrorAt(), keyInfo(p.name()),
                    s.resetRequestsAt(), s.resetTokensAt(), s.limitsSeenAt(),
                    health.lastPing(p.name()).orElse(null), keyless(p.name()),
                    limitsView(s, now), s.excluded(), s.excludedReason(), s.excludedSince(),
                    discovery.lastProbe(p.name()).map(r -> new StatusSnapshot.ProbeView(r.ok(), r.method(),
                            r.httpStatus(), r.error(), r.latencyMs(), r.checkedAt())).orElse(null)));
        }
        // Провайдеры без ключа тоже показываем — со статусом skipped.
        for (ProviderProperties pp : props.providers()) {
            if (!enabled.contains(pp.name())) {
                String why = pp.disabledReason() != null ? pp.disabledReason() : "api-key не задан";
                providers.add(new ProviderStatus(pp.name(), pp.model(), pp.priority(), "skipped",
                        null, why, null, null, null,
                        pp.limits() != null ? pp.limits().rpm() : null, null,
                        pp.limits() != null ? pp.limits().rpd() : null,
                        0, 0, 0, null, null, null, null, null, null, null, null, false,
                        null, false, null, null, null));
            }
        }
        providers.sort((a, b) -> Integer.compare(a.priority(), b.priority()));

        return new StatusSnapshot(now, providers,
                new StatusSnapshot.Totals(today.size(), tokens(today), today.stream().filter(r -> !r.isSuccess()).count()),
                hourly(last24h, now),
                repository.findTop20ByOrderByCreatedAtDescIdDesc().stream().map(r -> new RecentRequest(
                        r.getCreatedAt(), r.getProvider(), r.getModel(), r.getStatus(), r.getLatencyMs(),
                        r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens(), r.getError())).toList(),
                routing());
    }

    public RoutingInfo routing() {
        var s = router.routing();
        var cfg = routingSettings.configDefault();
        return new RoutingInfo(s.mode(), s.primary(), router.names(),
                router.previewOrder().stream().map(LlmProvider::name).toList(),
                routingOptions(), routingSettings.source(), routingSettings.warning(),
                new RoutingInfo.ConfigDefault(cfg.mode(), cfg.primary()));
    }

    /**
     * Все настроенные провайдеры (по priority) с живым состоянием — для выпадающего списка primary.
     * Cooldown и исчерпанный лимит выбор не запрещают (primary сохраняется, роутер вернётся к нему сам);
     * нельзя выбрать только провайдера без api-key (он не создан как бин и запрос к нему не уйдёт).
     */
    private List<RoutingInfo.ProviderOption> routingOptions() {
        Instant now = clock.instant();
        List<RoutingInfo.ProviderOption> options = new ArrayList<>();
        Set<String> enabled = router.providers().stream().map(LlmProvider::name).collect(Collectors.toSet());
        for (LlmProvider p : router.providers()) {
            ProviderState st = p.state();
            String blocked = st.blockedBy(now);
            options.add(new RoutingInfo.ProviderOption(p.name(), p.priority(), true,
                    blocked == null ? "healthy" : blocked,
                    blocked == null ? null : st.blockedUntil(now),
                    "cooldown".equals(blocked) ? st.cooldownReason() : null,
                    keyless(p.name())));
        }
        for (ProviderProperties pp : props.providers()) {
            if (!enabled.contains(pp.name())) {
                String why = pp.disabledReason() != null ? pp.disabledReason() : "api-key не задан";
                options.add(new RoutingInfo.ProviderOption(pp.name(), pp.priority(), false,
                        pp.enabled() ? "invalid" : "no_key", null, why, false));
            }
        }
        options.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        return options;
    }

    /** Лимиты провайдера для дашборда: значение + использовано + остаток + источник по каждому измерению. */
    static StatusSnapshot.LimitsView limitsView(ProviderState s, Instant now) {
        var e = s.effectiveLimits();
        var b = s.budget();
        return new StatusSnapshot.LimitsView(
                usage(e.rpm(), s.limits().lastMinute(now), null),
                usage(e.rpd(), s.limits().lastDay(now), null),
                usage(e.tpm(), b.minuteTokens(now), b.minuteRemaining(now)),
                usage(e.tokensPerDay(), b.dayTokens(now), null),
                usage(e.tokensPerMonth(), b.monthTokens(now), null));
    }

    private static StatusSnapshot.LimitUsage usage(ru.akiev.gateway.limits.EffectiveLimits.LimitValue v, long used,
            Long remaining) {
        Long rem = remaining != null ? Long.valueOf(Math.max(0, remaining))
                : v.value() == null ? null : Long.valueOf(Math.max(0, v.value() - used));
        return new StatusSnapshot.LimitUsage(v.value(), used, rem, v.source());
    }

    private StatusSnapshot.KeyInfo keyInfo(String provider) {
        if (!openRouter.enabled() || !provider.equals(openRouter.providerName())) {
            return null;
        }
        return new StatusSnapshot.KeyInfo(openRouter.data(), openRouter.fetchedAt(), openRouter.error());
    }

    /** 24 часовых корзины (последняя — текущий час), в каждой — запросы по провайдерам. */
    private List<HourBucket> hourly(List<UsageRecord> records, Instant now) {
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        List<HourBucket> buckets = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            Instant h = currentHour.minus(Duration.ofHours(i));
            Instant end = h.plus(Duration.ofHours(1));
            Map<String, Long> counts = records.stream()
                    .filter(r -> !r.getCreatedAt().isBefore(h) && r.getCreatedAt().isBefore(end))
                    .collect(Collectors.groupingBy(UsageRecord::getProvider, LinkedHashMap::new, Collectors.counting()));
            buckets.add(new HourBucket(h, counts));
        }
        return buckets;
    }

    private static long tokens(List<UsageRecord> rs) {
        return rs.stream().mapToLong(r -> r.getTotalTokens() != null ? r.getTotalTokens() : 0).sum();
    }
}
