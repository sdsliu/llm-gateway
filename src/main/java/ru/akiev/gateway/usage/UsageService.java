package ru.akiev.gateway.usage;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.router.RouterListener;

/** Сохраняет каждую попытку в usage_record и публикует событие для дашборда. */
@Service
public class UsageService implements RouterListener {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageRecordRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UsageService(UsageRecordRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void onSuccess(LlmProvider provider, ChatResponse response, long latencyMs) {
        Usage u = response != null ? response.usage() : null;
        String model = response != null && response.model() != null ? response.model()
                : provider.models().stream().findFirst().orElse(null);
        save(new UsageRecord(provider.name(), model,
                u != null ? u.promptTokens() : null,
                u != null ? u.completionTokens() : null,
                u != null ? u.totalTokens() : null,
                200, latencyMs, null, clock.instant()));
    }

    @Override
    public void onFailure(LlmProvider provider, ProviderException error, long latencyMs) {
        provider.state().recordError(clock.instant(), "HTTP " + error.status());
        save(new UsageRecord(provider.name(), provider.models().stream().findFirst().orElse(null),
                null, null, null, error.status(), latencyMs, error.getMessage(), clock.instant()));
    }

    private void save(UsageRecord r) {
        try {
            events.publishEvent(new UsageRecordedEvent(repository.save(r)));
        } catch (RuntimeException e) {
            // Учёт не должен ломать ответ клиенту: если БД недоступна — только лог.
            log.error("Не удалось сохранить usage_record: {}", e.getMessage());
        }
    }
}
