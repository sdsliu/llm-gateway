package ru.akiev.gateway.limits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.provider.RateLimitHeaders;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageRecord;
import ru.akiev.gateway.usage.UsageRecordRepository;

/**
 * Узнаёт лимиты провайдеров и следит, кто из них жив (issue #34, п. 1, 3, 8).
 *
 * <p><b>При старте, ДО приёма запросов</b>: {@link SmartInitializingSingleton#afterSingletonsInstantiated()}
 * Spring вызывает, когда все бины созданы, но веб-сервер (Tomcat) ещё не открыл порт — он стартует позже,
 * на этапе SmartLifecycle. Поэтому к первому запросу клиента:
 * <ol>
 *   <li>счётчики запросов и токенов (сутки, месяц, последняя минута) восстановлены из usage_record;</li>
 *   <li>все провайдеры опрошены параллельно ({@link ProviderProber}), у каждого свой таймаут
 *       (gateway.limits.probe-timeout) — старт не зависнет, даже если провайдер молчит;</li>
 *   <li>не ответившие (таймаут, 5xx, 401, 429) ИСКЛЮЧЕНЫ из роутинга — это видно в логе и на дашборде.</li>
 * </ol>
 *
 * <p><b>Раз в gateway.limits.reprobe-interval (≈ минута)</b> перепингуем исключённых и поставленных на паузу
 * после 429; ответил с годными лимитами — сразу возвращаем в роутинг. У работающих раз в refresh-interval
 * обновляем лимиты из API (OpenRouter /key, Gonka /test-auth).
 *
 * <p>gateway.limits.startup-probe=false (тесты) — никаких опросов: все провайдеры в роутинге, лимиты из конфига.
 */
@Service
public class LimitDiscoveryService implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LimitDiscoveryService.class);

    private final ProviderRouter router;
    private final Map<String, ProviderProperties> props;
    private final LimitsProperties limits;
    private final ProviderProber prober;
    private final UsageRecordRepository repository;
    private final Clock clock;
    private final Map<String, ProbeResult> lastProbe = new ConcurrentHashMap<>();

    public LimitDiscoveryService(ProviderRouter router, GatewayProperties gateway, LimitsProperties limits,
            WebClient.Builder builder, UsageRecordRepository repository, Clock clock) {
        this.router = router;
        this.props = gateway.providers().stream()
                .collect(Collectors.toMap(ProviderProperties::name, Function.identity(), (a, b) -> a));
        this.limits = limits;
        this.prober = new ProviderProber(builder, clock);
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        restoreCounters();
        if (!limits.startupProbe()) {
            log.info("Опрос провайдеров отключён (gateway.limits.startup-probe=false): все включённые в роутинге, "
                    + "лимиты из конфига");
            router.providers().forEach(p -> log.info("Провайдер {}: лимиты {}", p.name(), p.state().effectiveLimits()));
            return;
        }
        log.info("Опрос провайдеров перед стартом: {} (таймаут на каждого {})", router.names(), limits.probeTimeout());
        probe(router.providers(), true);
        List<String> live = router.providers().stream().filter(p -> !p.state().excluded()).map(LlmProvider::name).toList();
        if (live.isEmpty() && !router.providers().isEmpty()) {
            log.warn("Ни один провайдер не ответил при старте — запросы будут получать 503, пока кто-то не оживёт "
                    + "(перепроверка каждые {})", limits.reprobeInterval());
        } else {
            log.info("В роутинге: {}", live);
        }
    }

    /**
     * Перепроверка по расписанию. fixedDelay — следующий запуск через interval ПОСЛЕ окончания предыдущего,
     * поэтому медленный опрос не наслаивается сам на себя.
     */
    @Scheduled(fixedDelayString = "${gateway.limits.reprobe-interval:PT1M}",
            initialDelayString = "${gateway.limits.reprobe-interval:PT1M}")
    public void reprobe() {
        if (!limits.startupProbe()) {
            return;
        }
        Instant now = clock.instant();
        List<LlmProvider> targets = new ArrayList<>();
        for (LlmProvider p : router.providers()) {
            ProviderState s = p.state();
            Instant paused = s.pausedBy429At(now);
            ProbeResult last = lastProbe.get(p.name());
            ProbeKind kind = kind(p);
            boolean apiRefresh = (kind == ProbeKind.OPENROUTER || kind == ProbeKind.GONKA) && last != null
                    && last.checkedAt() != null && !last.checkedAt().plus(limits.refreshInterval()).isAfter(now);
            // Пауза от 429 «созрела» — прошло хотя бы полинтервала (тики раз в interval → ≈ раз в минуту).
            boolean pauseDue = paused != null && !paused.plus(limits.reprobeInterval().dividedBy(2)).isAfter(now);
            if (s.excluded() || pauseDue || apiRefresh) {
                targets.add(p);
            }
        }
        if (!targets.isEmpty()) {
            probe(targets, false);
        }
    }

    /** Опросить список провайдеров параллельно и применить результаты. Возвращает результаты по имени. */
    public Map<String, ProbeResult> probe(List<LlmProvider> targets, boolean startup) {
        // Flux.fromIterable → поток провайдеров; flatMap запускает опросы ПАРАЛЛЕЛЬНО (не дожидаясь предыдущего),
        // каждый опрос — свой Mono со своим timeout. collectMap собирает всё в Map, когда завершатся все.
        // block(общий таймаут) — здесь мы в обычном (не реактивном) потоке и честно ждём результат;
        // общий таймаут — страховка сверху, по отдельности каждый опрос и так ограничен probe-timeout.
        Duration overall = limits.probeTimeout().multipliedBy(2).plusSeconds(5);
        Map<String, ProbeResult> results;
        try {
            results = Flux.fromIterable(targets)
                    .flatMap(p -> config(p)
                            .map(c -> prober.probe(c, limits.probeTimeout()))
                            .orElseGet(() -> Mono.just(ProbeResult.ok("нет конфига", null, null, null, null)))
                            .map(r -> Map.entry(p.name(), r)))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .block(overall);
        } catch (RuntimeException e) {
            log.warn("Опрос провайдеров не уложился в {}: {}", overall, e.toString());
            results = Map.of();
        }
        Map<String, ProbeResult> out = new HashMap<>();
        for (LlmProvider p : targets) {
            ProbeResult r = results == null ? null : results.get(p.name());
            if (r == null) {
                r = ProbeResult.failed("—", null, "нет ответа за " + overall.toMillis() + " мс").timed(overall.toMillis(),
                        clock.instant());
            }
            apply(p, r, startup);
            out.put(p.name(), r);
        }
        return out;
    }

    /** Применить результат опроса к провайдеру: лимиты, включение/исключение, лог. */
    void apply(LlmProvider p, ProbeResult r, boolean startup) {
        lastProbe.put(p.name(), r);
        ProviderState s = p.state();
        Instant now = clock.instant();
        if (!r.ok()) {
            boolean was = s.excluded();
            boolean includedBefore = !was;
            if (startup || was || s.pausedBy429At(now) != null) {
                s.exclude(now, r.error());
                if (!was) {
                    log.warn("Провайдер {} ИСКЛЮЧЁН из роутинга: {} ({}). Перепроверка каждые {}",
                            p.name(), r.error(), r.method(), limits.reprobeInterval());
                } else {
                    log.info("Провайдер {} всё ещё не отвечает: {}", p.name(), r.error());
                }
            } else if (includedBefore) {
                // Плановое обновление лимитов у работающего провайдера не удалось — не исключаем:
                // реальные запросы сами покажут, жив ли он (и при 429/5xx роутер поставит паузу).
                log.warn("Провайдер {}: обновить лимиты не удалось ({}), оставляем прежние", p.name(), r.error());
            }
            return;
        }
        s.applyApiLimits(r.api());
        if (r.headers() != null) {
            if (p instanceof OpenAiCompatibleProvider o) {
                o.updateLimits(r.headers());
            } else {
                s.applyHeaderLimits(RateLimitHeaders.limits(r.headers(), kind(p).requestsHeaderIsPerDay()));
            }
            if (kind(p) == ProbeKind.GROQ && r.httpStatus() != null) {
                s.recordRequest(now); // крошечный chat — тоже запрос в счёт RPM/RPD
            }
        }
        // Паузу после 429 успешный ping не снимает: GET /models у SambaNova отвечает 200,
        // пока chat ещё в лимите. Ждём Retry-After / default-cooldown, иначе следующий запрос снова получит 429.
        Instant rateLimitUntil = s.cooldownUntil();
        boolean rateHold = s.pausedBy429At(now) != null && rateLimitUntil != null && now.isBefore(rateLimitUntil);
        String rateReason = s.cooldownReason();
        boolean returned = s.excluded();
        if (returned) {
            s.include();
            if (rateHold) {
                s.pauseAfter429(now, rateLimitUntil, rateReason != null ? rateReason : "HTTP 429");
            }
        }
        String status = r.httpStatus() != null ? "HTTP " + r.httpStatus() : "без запроса";
        String note = r.error() != null ? "; " + r.error() : "";
        if (returned && !rateHold) {
            log.info("Провайдер {} снова отвечает ({} → {}, {} мс) — возвращён в роутинг. Лимиты: {}",
                    p.name(), r.method(), status, r.latencyMs(), s.effectiveLimits());
        } else if (rateHold) {
            log.info("Провайдер {} отвечает на ping ({} → {}), но пауза после 429 до {} — в чат не пускаем",
                    p.name(), r.method(), status, s.cooldownUntil());
        } else {
            log.info("Провайдер {}: доступен ({} → {}, {} мс{}). Лимиты: {}", p.name(), r.method(), status,
                    r.latencyMs(), note, s.effectiveLimits());
        }
        if (kind(p) == ProbeKind.GROQ && !ProviderProber.hasRateLimitHeaders(r.headers())) {
            log.warn("Провайдер {}: заголовков x-ratelimit-* в ответе нет — лимиты из конфига", p.name());
        }
    }

    /**
     * Восстановить счётчики из usage_record (п. 3): токены за текущие сутки и месяц (UTC), метки запросов за
     * последние 24 часа (RPD/RPM) и токены последней минуты (TPM). Без этого после рестарта шлюз «забыл» бы,
     * что суточная квота уже потрачена, и получил бы 429 от провайдера.
     */
    public void restoreCounters() {
        Instant now = clock.instant();
        try {
            Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant monthStart = LocalDate.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
            Map<String, Long> day = sums(repository.sumTokensByProviderSince(dayStart));
            Map<String, Long> month = sums(repository.sumTokensByProviderSince(monthStart));
            List<UsageRecord> last24h = repository.findByCreatedAtAfterOrderByCreatedAtAsc(now.minus(Duration.ofDays(1)));
            Instant minuteAgo = now.minus(TokenBudget.MINUTE);
            for (LlmProvider p : router.providers()) {
                List<UsageRecord> mine = last24h.stream().filter(r -> p.name().equals(r.getProvider())).toList();
                p.state().limits().restore(mine.stream().map(UsageRecord::getCreatedAt).toList(), now);
                List<TokenBudget.Reservation> minute = mine.stream()
                        .filter(r -> r.getCreatedAt().isAfter(minuteAgo) && r.getTotalTokens() != null)
                        .map(r -> TokenBudget.past(r.getCreatedAt(), r.getTotalTokens()))
                        .toList();
                long d = day.getOrDefault(p.name(), 0L);
                long m = month.getOrDefault(p.name(), 0L);
                p.state().budget().restore(now, d, m, minute);
                if (!mine.isEmpty() || m > 0) {
                    log.info("Провайдер {}: счётчики восстановлены из usage_record — запросов за 24 ч {}, "
                            + "токенов сегодня {}, за месяц {}", p.name(), mine.size(), d, m);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось восстановить счётчики из usage_record: {}", e.toString());
        }
    }

    private static Map<String, Long> sums(List<Object[]> rows) {
        Map<String, Long> m = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] instanceof Number n) {
                m.put((String) row[0], n.longValue());
            }
        }
        return m;
    }

    private Optional<ProviderProperties> config(LlmProvider p) {
        return Optional.ofNullable(props.get(p.name()));
    }

    private ProbeKind kind(LlmProvider p) {
        return config(p).map(ProbeKind::of).orElse(ProbeKind.NONE);
    }

    /** Последний результат опроса провайдера (для дашборда). */
    public Optional<ProbeResult> lastProbe(String name) {
        return Optional.ofNullable(lastProbe.get(name));
    }
}
