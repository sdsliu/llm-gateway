package ru.akiev.gateway.router;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    public ProviderRouter(List<LlmProvider> providers, Duration defaultCooldown, Clock clock) {
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
                return p.chat(request);
            } catch (ProviderException e) {
                if (!e.isRetriable()) {
                    throw e;
                }
                Duration cd = e.retryAfter() != null ? e.retryAfter() : defaultCooldown;
                p.state().cooldown(clock.instant().plus(cd), "HTTP " + e.status());
                log.warn("Failover: {} ответил {} — cooldown {}, пробуем следующего. {}",
                        p.name(), e.status(), cd, e.getMessage());
                failures.add(p.name() + ": HTTP " + e.status());
            }
        }
        throw new AllProvidersUnavailableException(
                "All providers are unavailable" + (failures.isEmpty() ? "" : " (" + String.join(", ", failures) + ")"));
    }
}
