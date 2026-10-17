package ru.akiev.gateway.router;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.List;
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
 * <p>Это «последовательный» failover внутри одного HTTP-запроса клиента: клиент ничего не
 * знает о переключениях, он просто получает ответ (чуть позже).
 */
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final List<LlmProvider> providers;
    private final Duration defaultCooldown;
    private final Clock clock;
    private final RouterListener listener;

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

    public ChatResponse chat(ChatRequest request) {
        List<String> failures = new ArrayList<>();
        for (LlmProvider p : providers) {
            Instant now = clock.instant();
            if (!p.state().isAvailable(now)) {
                log.debug("Пропускаем {}: недоступен (cooldown до {}, причина: {})",
                        p.name(), p.state().cooldownUntil(), p.state().cooldownReason());
                failures.add(p.name() + ": unavailable");
                continue;
            }
            try {
                log.info("Запрос → провайдер {} (priority {})", p.name(), p.priority());
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
                if (!e.isRetriable()) {
                    // 400/404 и т.п. — не failover, но обязательно в лог (например, OpenRouter 404
                    // «No endpoints found» для снятой :free модели).
                    log.warn("Провайдер {} ответил {} (без failover): {}", p.name(), e.status(), snippet(e.getMessage()));
                    throw e;
                }
                Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
                p.state().cooldown(clock.instant().plus(cd), "HTTP " + e.status());
                log.warn("Failover: {} ответил {} — cooldown {}, пробуем следующего. {}",
                        p.name(), e.status(), cd, snippet(e.getMessage()));
                failures.add(p.name() + ": HTTP " + e.status());
            }
        }
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
        for (LlmProvider p : providers) {
            if (!p.state().isAvailable(clock.instant())) {
                failures.add(p.name() + ": unavailable");
                continue;
            }
            log.info("Стрим → провайдер {} (priority {})", p.name(), p.priority());
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
                if (!e.isRetriable()) {
                    // 400/404 и т.п. — не failover, но обязательно в лог (например, OpenRouter 404
                    // «No endpoints found» для снятой :free модели).
                    log.warn("Провайдер {} ответил {} (без failover): {}", p.name(), e.status(), snippet(e.getMessage()));
                    throw e;
                }
                Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
                p.state().cooldown(clock.instant().plus(cd), "HTTP " + e.status());
                log.warn("Failover (стрим, до первого чанка): {} ответил {} — cooldown {}. {}", p.name(), e.status(), cd,
                        snippet(e.getMessage()));
                failures.add(p.name() + ": HTTP " + e.status());
            }
        }
        throw new AllProvidersUnavailableException(
                "All providers are unavailable" + (failures.isEmpty() ? "" : " (" + String.join(", ", failures) + ")"));
    }

    private static final int STREAM_PREFETCH = 32;

    /** Короткий фрагмент ошибки для логов (тело ответа провайдера, без заголовков и ключей). */
    static String snippet(String msg) {
        if (msg == null) {
            return "";
        }
        String m = msg.replaceAll("\\s+", " ").trim();
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
