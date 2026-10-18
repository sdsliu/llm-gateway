package ru.akiev.gateway.router;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.limits.RequestEstimator;
import ru.akiev.gateway.limits.TokenBudget;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.provider.ProviderState;
import ru.akiev.gateway.usage.RequestTags;

/**
 * Роутер: для каждого запроса перебирает провайдеров по приоритету и отдаёт ответ первого,
 * кто справился.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Сортируем провайдеров по priority (меньше = раньше).</li>
 *   <li>Пропускаем тех, кто в cooldown или упёрся в свои rpm/rpd / remaining = 0.</li>
 *   <li>Пробуем провайдера. Успех — возвращаем ответ.</li>
 *   <li>429 / 5xx / таймаут / сеть — ставим cooldown (Retry-After или 5 минут по умолчанию)
 *       и идём к следующему (failover).</li>
 *   <li>Прочие 4xx (например 400 — кривой запрос) отдаём клиенту сразу: другой провайдер
 *       ответит так же.</li>
 *   <li>Никого не осталось — {@link AllProvidersUnavailableException} → HTTP 503.</li>
 * </ol>
 *
 * <p>Порядок перебора задаёт режим роутинга ({@link RoutingMode}, меняется в runtime через
 * {@link #setRouting}): PRIORITY — по priority (выбранный primary — первым), ROUND_ROBIN — по кругу
 * среди доступных сейчас провайдеров, остальные шаги алгоритма (пропуск, failover) те же.
 *
 * <p>Это «последовательный» failover внутри одного HTTP-запроса клиента: клиент ничего не
 * знает о переключениях, он просто получает ответ (чуть позже).
 *
 * <p><b>Лимиты (issue #34).</b> Перед отправкой запрос оценивается ({@link RequestEstimator}: вход + max_tokens).
 * Кандидаты — провайдеры, у которых есть запрошенная модель и хватает места по ВСЕМ лимитам
 * (RPM, RPD, TPM, токены в сутки и в месяц, см. {@link ProviderState#capacity}). Под выбранного провайдера
 * токены резервируются сразу ({@link TokenBudget#reserve}), после ответа резерв заменяется реальным usage.
 * <ul>
 *   <li>Запрос больше TPM всех провайдеров — сразу 413 request_too_large (ждать бесполезно).</li>
 *   <li>Места нет ни у кого сейчас — ждём до gateway.limits.max-wait (поток Tomcat спит: у нас Spring MVC,
 *       «один запрос = один поток»), потом 429 rate_limit_exceeded с Retry-After.</li>
 *   <li>429 от провайдера — пауза ({@link ProviderState#pauseAfter429}) и failover; паузу досрочно снимает
 *       первый успешный ping LimitDiscoveryService.</li>
 *   <li>pin-model-per-run — запросы с одним x-run-id идут к одному провайдеру, пока он доступен.</li>
 * </ul>
 */
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final List<LlmProvider> providers;
    private final Duration defaultCooldown;
    private final Clock clock;
    private final RouterListener listener;
    /** Текущий режим. volatile + неизменяемый record: запросы читают без блокировок. */
    private volatile RoutingSettings routing = RoutingSettings.DEFAULT;
    /** Счётчик round-robin: getAndIncrement атомарен, два параллельных запроса не получат один старт. */
    private final AtomicLong rrCounter = new AtomicLong();
    private final LimitsProperties limits;
    private final RequestEstimator estimator;
    private final Sleeper sleeper;
    private final RunPins pins;
    /** Выбор + резерв токенов под одним замком: два параллельных запроса не займут один и тот же остаток. */
    private final Object selectLock = new Object();

    /** Как ждать освобождения лимита. В проде — Thread.sleep, в тестах — «перемотка» фейковых часов. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;

        Sleeper THREAD = d -> Thread.sleep(Math.max(1, d.toMillis()));
    }

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock) {
        this(providers, defaultCooldown, clock, RouterListener.NOOP);
    }

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock,
            RouterListener listener) {
        this(providers, defaultCooldown, clock, listener, LimitsProperties.LEGACY, Sleeper.THREAD);
    }

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock,
            RouterListener listener, LimitsProperties limits, Sleeper sleeper) {
        this.listener = listener;
        this.providers = providers.stream().sorted(Comparator.comparingInt(LlmProvider::priority)).toList();
        this.defaultCooldown = defaultCooldown != null ? defaultCooldown : Duration.ofMinutes(5);
        this.clock = clock;
        this.limits = limits != null ? limits : LimitsProperties.LEGACY;
        this.estimator = new RequestEstimator(this.limits.defaultMaxTokens());
        this.sleeper = sleeper != null ? sleeper : Sleeper.THREAD;
        this.pins = new RunPins(this.limits.pinTtl());
    }

    public LimitsProperties limitsSettings() {
        return limits;
    }

    public List<LlmProvider> providers() {
        return providers;
    }

    public RoutingSettings routing() {
        return routing;
    }

    /**
     * Сменить режим роутинга в памяти (сохранение в БД — RoutingSettingsService). primary должен быть
     * одним из включённых провайдеров (с api-key или keyless), иначе {@link IllegalArgumentException}.
     * Cooldown / исчерпанный лимит primary выбор не запрещают: пока он недоступен, основной цикл его
     * пропускает и идёт к следующему по priority, а когда пауза закончится — снова начинает с него.
     */
    public synchronized RoutingSettings setRouting(RoutingSettings next) {
        if (next.primary() != null && providers.stream().noneMatch(p -> p.name().equals(next.primary()))) {
            throw new IllegalArgumentException("Unknown or disabled provider: " + next.primary()
                    + " (available: " + String.join(", ", names()) + ")");
        }
        RoutingSettings prev = routing;
        routing = next;
        if (!prev.equals(next)) {
            log.info("Режим роутинга изменён: {} → {}", prev, next);
        }
        return next;
    }

    public List<String> names() {
        return providers.stream().map(LlmProvider::name).toList();
    }

    /** Порядок, в котором будет перебирать провайдеров следующий запрос (без сдвига round-robin). */
    public List<LlmProvider> previewOrder() {
        return order(false);
    }

    /**
     * Порядок перебора для одного запроса.
     * <ul>
     *   <li>PRIORITY: по priority, primary (если задан) — первым;</li>
     *   <li>ROUND_ROBIN: доступные сейчас провайдеры, сдвинутые на очередной шаг счётчика; недоступные
     *       (cooldown/лимит) — в хвосте, их пропустит основной цикл (и запишет в причины 503).
     *       При ошибке цикл просто идёт к следующему в этом списке — это и есть failover.</li>
     * </ul>
     */
    List<LlmProvider> order(boolean advance) {
        RoutingSettings s = routing;
        if (s.mode() == RoutingMode.ROUND_ROBIN) {
            Instant now = clock.instant();
            List<LlmProvider> available = providers.stream().filter(p -> p.state().isAvailable(now)).toList();
            if (available.isEmpty()) {
                return providers;
            }
            long n = advance ? rrCounter.getAndIncrement() : rrCounter.get();
            int start = (int) Math.floorMod(n, (long) available.size());
            List<LlmProvider> result = new ArrayList<>(providers.size());
            result.addAll(available.subList(start, available.size()));
            result.addAll(available.subList(0, start));
            providers.stream().filter(p -> !available.contains(p)).forEach(result::add);
            return result;
        }
        if (s.mode() == RoutingMode.HEADROOM || s.primary() == null) {
            // HEADROOM: порядок по priority нужен только для «ничьей» — выбирает select() по запасу.
            return providers;
        }
        List<LlmProvider> result = new ArrayList<>(providers.size());
        providers.stream().filter(p -> p.name().equals(s.primary())).forEach(result::add);
        providers.stream().filter(p -> !p.name().equals(s.primary())).forEach(result::add);
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Выбор провайдера по лимитам
    // ---------------------------------------------------------------------------------------------

    /** Выбранный провайдер и резерв токенов под запрос. */
    record Selection(LlmProvider provider, TokenBudget.Reservation reservation) {
    }

    /** Состояние перебора одного клиентского запроса. */
    private final class Attempt {
        final ChatRequest request;
        final long tokens;
        final List<LlmProvider> ordered = order(true);
        final Set<LlmProvider> tried = new HashSet<>();
        final List<String> failures = new ArrayList<>();
        final List<String> tooLarge = new ArrayList<>();
        final String runId = RequestTags.current().runId();
        final Instant deadline;
        int attempted;
        /** Ближайший момент освобождения среди провайдеров, упёршихся в лимит (для Retry-After). */
        Instant limitRetryAt;
        boolean limitBlocked;

        Attempt(ChatRequest request) {
            this.request = request;
            this.tokens = estimator.estimate(request).total();
            this.deadline = clock.instant().plus(limits.maxWait());
        }

        /** Провайдеры, у которых есть запрошенная модель; если её нет ни у кого — все (как до #34). */
        List<LlmProvider> modelCandidates() {
            String model = request.model();
            List<LlmProvider> withModel = model == null ? List.of()
                    : ordered.stream().filter(p -> p.models().contains(model)).toList();
            return withModel.isEmpty() ? ordered : withModel;
        }
    }

    /**
     * Запрос больше TPM у всех провайдеров, кроме исключённых (и у каждого TPM известен) — 413 сразу,
     * не тратя ни одной попытки.
     */
    private void rejectIfLargerThanEveryTpm(Attempt a) {
        Instant now = clock.instant();
        List<LlmProvider> live = a.modelCandidates().stream().filter(p -> !p.state().excluded()).toList();
        if (live.isEmpty()) {
            return;
        }
        boolean all = live.stream().allMatch(p -> p.state().capacity(now, a.tokens).neverFits());
        if (all) {
            String tpms = String.join(", ", live.stream()
                    .map(p -> p.name() + " tpm=" + p.state().budget().tpm()).toList());
            log.warn("Запрос ~{} токенов больше TPM всех провайдеров ({}) — 413 без попыток", a.tokens, tpms);
            throw new RequestTooLargeException("Request too large: ~" + a.tokens + " tokens (input + max_tokens) exceeds "
                    + "the tokens-per-minute limit of every provider (" + tpms + "). Reduce the prompt or max_tokens.");
        }
    }

    /**
     * Следующий провайдер для попытки или null, если больше некого (тогда вызывающий бросит 429/503).
     *
     * <p>Если подходящие есть, но все упёрлись в минутные лимиты, которые освободятся до дедлайна, —
     * спим до ближайшего освобождения и пробуем снова.
     */
    private Selection next(Attempt a) {
        boolean pinFallbackLogged = false;
        while (true) {
            Instant now = clock.instant();
            Instant wakeAt = null;
            synchronized (selectLock) {
                List<LlmProvider> candidates = new ArrayList<>(a.modelCandidates());
                candidates.removeAll(a.tried);
                String pinned = limits.pinModelPerRun() ? pins.get(a.runId, now) : null;
                LlmProvider pinnedProvider = pinned == null ? null
                        : candidates.stream().filter(p -> p.name().equals(pinned)).findFirst().orElse(null);
                LlmProvider best = null;
                double bestHeadroom = Double.NEGATIVE_INFINITY;
                boolean pinnedWaitable = false;
                a.limitBlocked = false;
                a.limitRetryAt = null;
                for (LlmProvider p : candidates) {
                    ProviderState.Capacity c = p.state().capacity(now, a.tokens);
                    if (!c.fits()) {
                        boolean limit = isLimit(c.blockedBy()) && !c.neverFits();
                        if (limit) {
                            a.limitBlocked = true;
                            if (c.retryAt() != null && (a.limitRetryAt == null || c.retryAt().isBefore(a.limitRetryAt))) {
                                a.limitRetryAt = c.retryAt();
                            }
                            if (c.retryAt() != null && !c.retryAt().isAfter(a.deadline)) {
                                wakeAt = wakeAt == null || c.retryAt().isBefore(wakeAt) ? c.retryAt() : wakeAt;
                                pinnedWaitable |= p == pinnedProvider;
                            }
                        }
                        log.debug("Пропускаем {}: {} (до {})", p.name(), c.blockedBy(), c.retryAt());
                        continue;
                    }
                    if (pinnedProvider != null && p != pinnedProvider) {
                        continue; // run привязан к другому провайдеру — этот пока не рассматриваем
                    }
                    // HEADROOM — максимум запаса; остальные режимы — первый подходящий в порядке order().
                    if (routing.mode() == RoutingMode.HEADROOM ? c.headroom() > bestHeadroom : best == null) {
                        best = p;
                        bestHeadroom = c.headroom();
                    }
                }
                if (best != null) {
                    return reserve(a, best, now, pinned);
                }
                if (pinnedProvider != null && !pinnedWaitable) {
                    // Привязанный провайдер недоступен надолго (cooldown/исключён/сутки) — снимаем привязку.
                    if (!pinFallbackLogged) {
                        log.warn("x-run-id {}: привязанный провайдер {} недоступен — переключаемся на другого",
                                a.runId, pinned);
                        pinFallbackLogged = true;
                    }
                    a.tried.add(pinnedProvider);
                    continue;
                }
            }
            if (wakeAt == null) {
                for (LlmProvider p : a.modelCandidates()) {
                    if (!a.tried.contains(p)) {
                        a.failures.add(p.name() + ": " + describe(p.state().capacity(now, a.tokens)));
                    }
                }
                return null;
            }
            Duration sleep = Duration.between(now, wakeAt);
            sleep = sleep.isNegative() || sleep.isZero() ? Duration.ofMillis(50) : sleep;
            log.info("Места под запрос (~{} токенов) нет ни у кого — ждём {} мс (макс. до {})",
                    a.tokens, sleep.toMillis(), a.deadline);
            try {
                sleeper.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private Selection reserve(Attempt a, LlmProvider p, Instant now, String pinned) {
        TokenBudget.Reservation r = p.state().budget().reserve(now, a.tokens);
        if (limits.pinModelPerRun() && a.runId != null) {
            String prev = pins.put(a.runId, p.name(), now);
            if (prev != null && !prev.equals(p.name())) {
                log.warn("x-run-id {}: провайдер сменился {} → {}", a.runId, prev, p.name());
            } else if (pinned == null) {
                log.info("x-run-id {} привязан к провайдеру {}", a.runId, p.name());
            }
        }
        a.tried.add(p);
        return new Selection(p, r);
    }

    private static boolean isLimit(String blockedBy) {
        return blockedBy != null && !"excluded".equals(blockedBy) && !"cooldown".equals(blockedBy);
    }

    private static String describe(ProviderState.Capacity c) {
        if (c.neverFits()) {
            return "request larger than tpm";
        }
        if (c.blockedBy() == null) {
            return "unavailable";
        }
        return switch (c.blockedBy()) {
            case "excluded" -> "excluded (no ping response)";
            case "cooldown" -> "unavailable";
            default -> "limit " + c.blockedBy();
        };
    }

    /** Никого не осталось: 413 (всем велик), 429 (упёрлись в лимиты) или 503 (все упали / в паузе). */
    private RuntimeException exhausted(Attempt a) {
        throwIfAllTooLarge(a.tooLarge, a.attempted);
        String reasons = a.failures.isEmpty() ? "" : " (" + String.join(", ", a.failures) + ")";
        if (a.limitBlocked) {
            Instant now = clock.instant();
            Duration retry = a.limitRetryAt != null && a.limitRetryAt.isAfter(now)
                    ? Duration.between(now, a.limitRetryAt) : Duration.ofMinutes(1);
            log.warn("Лимиты всех провайдеров исчерпаны{} — 429, повторить через {}", reasons, retry);
            return new RateLimitedException("Rate limit exceeded on all providers" + reasons, retry);
        }
        return new AllProvidersUnavailableException("All providers are unavailable" + reasons);
    }

    /** Ответ без usage — резерв остаётся оценкой; ошибка до генерации (4xx/5xx кроме таймаута) — токены не потрачены. */
    private static void settleFailure(Selection s, ProviderException e) {
        if (e.status() != 504 && e.status() != 499) {
            s.provider().state().budget().settle(s.reservation(), 0L);
        }
    }

    private static Long usageTokens(ChatResponse resp) {
        return resp != null && resp.usage() != null && resp.usage().totalTokens() != null
                ? resp.usage().totalTokens().longValue() : null;
    }

    /**
     * Общая обработка ошибки попытки: 413 — без паузы; 4xx — наружу без failover; 429 — пауза до первого
     * успешного ping; прочие (5xx, таймаут) — cooldown. Возвращает нормально, если надо пробовать следующего.
     */
    private void onAttemptFailure(Attempt a, LlmProvider p, ProviderException e, String what) {
        if (e.isRequestTooLarge()) {
            tooLarge(p, e, a.tooLarge, a.failures);
            return;
        }
        if (!e.isRetriable()) {
            // 400/404 и т.п. — не failover, но обязательно в лог (например, OpenRouter 404
            // «No endpoints found» для снятой :free модели).
            log.warn("Провайдер {} ответил {} (без failover): {}", p.name(), e.status(), snippet(e.getMessage()));
            throw e;
        }
        Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
        Instant now = clock.instant();
        if (e.status() == 429) {
            p.state().pauseAfter429(now, now.plus(cd), "HTTP 429");
            log.warn("{}: {} ответил 429 — пауза до {} или до первого успешного ping, пробуем следующего. {}",
                    what, p.name(), now.plus(cd), snippet(e.getMessage()));
        } else {
            p.state().cooldown(now.plus(cd), "HTTP " + e.status());
            log.warn("{}: {} ответил {} — cooldown {}, пробуем следующего. {}",
                    what, p.name(), e.status(), cd, snippet(e.getMessage()));
        }
        a.attempted++;
        a.failures.add(p.name() + ": HTTP " + e.status());
    }

    public ChatResponse chat(ChatRequest request) {
        Attempt a = new Attempt(request);
        rejectIfLargerThanEveryTpm(a);
        Selection s;
        while ((s = next(a)) != null) {
            LlmProvider p = s.provider();
            log.info("Запрос → провайдер {} (priority {}, режим {}, оценка ~{} токенов)",
                    p.name(), p.priority(), routing.mode(), a.tokens);
            long start = System.nanoTime();
            try {
                ChatResponse resp = p.chat(request);
                p.state().budget().settle(s.reservation(), usageTokens(resp));
                listener.onSuccess(p, resp, (System.nanoTime() - start) / 1_000_000);
                return resp;
            } catch (ProviderException e) {
                settleFailure(s, e);
                listener.onFailure(p, e, (System.nanoTime() - start) / 1_000_000);
                onAttemptFailure(a, p, e, "Failover");
            }
        }
        throw exhausted(a);
    }

    /**
     * Стриминг с failover ТОЛЬКО ДО ПЕРВОГО ЧАНКА.
     *
     * <p>Почему так: как только клиенту ушёл первый чанк, HTTP-ответ уже начат (статус 200 и
     * заголовки отправлены), а клиент начал собирать текст. Переключиться на другого провайдера
     * «посередине» нельзя: новый провайдер начнёт ответ с начала и с другим содержанием —
     * клиент получит склейку двух разных ответов. Поэтому:
     * <ul>
     *   <li>ошибка до первого чанка (429, 5xx, таймаут соединения) — cooldown и следующий провайдер,
     *       клиент ничего не замечает; если все упали — честный HTTP 503;</li>
     *   <li>ошибка после первого чанка — клиенту отправляется error-чанк, поток закрывается.</li>
     * </ul>
     *
     * <p>Как ждём первый чанк: {@code flux.toStream(prefetch)} — блокирующий мост из Flux в обычный
     * Java Stream (аналог block() для потоков). {@code iterator.hasNext()} блокирует поток, пока не
     * придёт первый элемент или ошибка. prefetch = сколько чанков Reactor запросит заранее —
     * это и есть ограничение буфера (backpressure).
     *
     * <p>Лимиты — как в {@link #chat}: резерв токенов при выборе, факт из usage-чанка — когда стрим закончится
     * (обёртка над listener ниже).
     */
    public StreamSession openStream(ChatRequest request) {
        Attempt a = new Attempt(request);
        rejectIfLargerThanEveryTpm(a);
        Selection s;
        while ((s = next(a)) != null) {
            LlmProvider p = s.provider();
            log.info("Стрим → провайдер {} (priority {}, режим {}, оценка ~{} токенов)",
                    p.name(), p.priority(), routing.mode(), a.tokens);
            long start = System.nanoTime();
            Stream<String> stream = p.chatStream(request).toStream(STREAM_PREFETCH);
            Iterator<String> it = stream.iterator();
            try {
                if (!it.hasNext()) {
                    // Провайдер закрыл поток, не прислав ни одного чанка — считаем сбоем.
                    throw new ProviderException(502, "Provider " + p.name() + " returned empty stream", null);
                }
                String first = it.next();
                return new StreamSession(p, stream, it, first, start, request.clientWantsStreamUsage(),
                        settling(s));
            } catch (ProviderException e) {
                stream.close();
                settleFailure(s, e);
                listener.onFailure(p, e, (System.nanoTime() - start) / 1_000_000);
                onAttemptFailure(a, p, e, "Failover (стрим, до первого чанка)");
            }
        }
        throw exhausted(a);
    }

    /** Listener стрима, который по окончании заменяет резерв токенов фактическим usage. */
    private RouterListener settling(Selection s) {
        return new RouterListener() {
            @Override
            public void onSuccess(LlmProvider provider, ChatResponse response, long latencyMs) {
                provider.state().budget().settle(s.reservation(), usageTokens(response));
                listener.onSuccess(provider, response, latencyMs);
            }

            @Override
            public void onFailure(LlmProvider provider, ProviderException error, long latencyMs) {
                listener.onFailure(provider, error, latencyMs);
            }
        };
    }

    private static final int STREAM_PREFETCH = 32;

    /** 413 / «Request too large»: без cooldown (провайдер исправен) и без повтора — сразу к следующему. */
    private static void tooLarge(LlmProvider p, ProviderException e, List<String> tooLarge, List<String> failures) {
        log.warn("Провайдер {}: запрос слишком большой (HTTP {}) — без cooldown, пробуем следующего. {}",
                p.name(), e.status(), snippet(e.getMessage()));
        tooLarge.add(p.name());
        failures.add(p.name() + ": request too large");
    }

    /**
     * Все провайдеры, к которым реально ходили, ответили «слишком большой запрос» — это ошибка клиента (4xx),
     * а не недоступность: повтор того же запроса не поможет. Текст провайдера (с id организации) не пробрасываем.
     */
    private static void throwIfAllTooLarge(List<String> tooLarge, int otherAttempted) {
        if (!tooLarge.isEmpty() && otherAttempted == 0) {
            throw new RequestTooLargeException("Request too large for all available providers ("
                    + String.join(", ", tooLarge) + "). Reduce the prompt size or split the task.");
        }
    }

    /** Короткий фрагмент ошибки для логов (тело ответа провайдера, без заголовков и ключей). */
    static String snippet(String msg) {
        if (msg == null) {
            return "";
        }
        String m = msg.replaceAll("\\s+", " ").trim();
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
