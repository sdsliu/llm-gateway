package ru.akiev.gateway.router;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;

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

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock) {
        this(providers, defaultCooldown, clock, RouterListener.NOOP);
    }

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock,
            RouterListener listener) {
        this.listener = listener;
        this.providers = providers.stream().sorted(Comparator.comparingInt(LlmProvider::priority)).toList();
        this.defaultCooldown = defaultCooldown != null ? defaultCooldown : Duration.ofMinutes(5);
        this.clock = clock;
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
        if (s.primary() == null) {
            return providers;
        }
        List<LlmProvider> result = new ArrayList<>(providers.size());
        providers.stream().filter(p -> p.name().equals(s.primary())).forEach(result::add);
        providers.stream().filter(p -> !p.name().equals(s.primary())).forEach(result::add);
        return result;
    }

    public ChatResponse chat(ChatRequest request) {
        List<String> failures = new ArrayList<>();
        List<String> tooLarge = new ArrayList<>();
        int attempted = 0;
        for (LlmProvider p : order(true)) {
            Instant now = clock.instant();
            if (!p.state().isAvailable(now)) {
                log.debug("Пропускаем {}: недоступен (cooldown до {}, причина: {})",
                        p.name(), p.state().cooldownUntil(), p.state().cooldownReason());
                failures.add(p.name() + ": unavailable");
                continue;
            }
            try {
                log.info("Запрос → провайдер {} (priority {}, режим {})", p.name(), p.priority(), routing.mode());
                long start = System.nanoTime();
                try {
                    ChatResponse resp = p.chat(request);
                    listener.onSuccess(p, resp, (System.nanoTime() - start) / 1_000_000);
                    return resp;
                } catch (ProviderException e) {
                    listener.onFailure(p, e, (System.nanoTime() - start) / 1_000_000);
                    throw e;
                }
            } catch (ProviderException e) {
                if (e.isRequestTooLarge()) {
                    tooLarge(p, e, tooLarge, failures);
                    continue;
                }
                if (!e.isRetriable()) {
                    // 400/404 и т.п. — не failover, но обязательно в лог (например, OpenRouter 404
                    // «No endpoints found» для снятой :free модели).
                    log.warn("Провайдер {} ответил {} (без failover): {}", p.name(), e.status(), snippet(e.getMessage()));
                    throw e;
                }
                Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
                p.state().cooldown(clock.instant().plus(cd), "HTTP " + e.status());
                attempted++;
                log.warn("Failover: {} ответил {} — cooldown {}, пробуем следующего. {}",
                        p.name(), e.status(), cd, snippet(e.getMessage()));
                failures.add(p.name() + ": HTTP " + e.status());
            }
        }
        throwIfAllTooLarge(tooLarge, attempted);
        throw new AllProvidersUnavailableException(
                "All providers are unavailable" + (failures.isEmpty() ? "" : " (" + String.join(", ", failures) + ")"));
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
     */
    public StreamSession openStream(ChatRequest request) {
        List<String> failures = new ArrayList<>();
        List<String> tooLarge = new ArrayList<>();
        int attempted = 0;
        for (LlmProvider p : order(true)) {
            if (!p.state().isAvailable(clock.instant())) {
                failures.add(p.name() + ": unavailable");
                continue;
            }
            log.info("Стрим → провайдер {} (priority {}, режим {})", p.name(), p.priority(), routing.mode());
            long start = System.nanoTime();
            Stream<String> stream = p.chatStream(request).toStream(STREAM_PREFETCH);
            Iterator<String> it = stream.iterator();
            try {
                if (!it.hasNext()) {
                    // Провайдер закрыл поток, не прислав ни одного чанка — считаем сбоем.
                    throw new ProviderException(502, "Provider " + p.name() + " returned empty stream", null);
                }
                String first = it.next();
                return new StreamSession(p, stream, it, first, start, request.clientWantsStreamUsage(), listener);
            } catch (ProviderException e) {
                stream.close();
                listener.onFailure(p, e, (System.nanoTime() - start) / 1_000_000);
                if (e.isRequestTooLarge()) {
                    tooLarge(p, e, tooLarge, failures);
                    continue;
                }
                if (!e.isRetriable()) {
                    // 400/404 и т.п. — не failover, но обязательно в лог (например, OpenRouter 404
                    // «No endpoints found» для снятой :free модели).
                    log.warn("Провайдер {} ответил {} (без failover): {}", p.name(), e.status(), snippet(e.getMessage()));
                    throw e;
                }
                Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
                p.state().cooldown(clock.instant().plus(cd), "HTTP " + e.status());
                attempted++;
                log.warn("Failover (стрим, до первого чанка): {} ответил {} — cooldown {}. {}", p.name(), e.status(), cd,
                        snippet(e.getMessage()));
                failures.add(p.name() + ": HTTP " + e.status());
            }
        }
        throwIfAllTooLarge(tooLarge, attempted);
        throw new AllProvidersUnavailableException(
                "All providers are unavailable" + (failures.isEmpty() ? "" : " (" + String.join(", ", failures) + ")"));
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
