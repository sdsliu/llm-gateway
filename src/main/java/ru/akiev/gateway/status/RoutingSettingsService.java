package ru.akiev.gateway.status;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/**
 * Режим роутинга, который переживает рестарт.
 *
 * <p>Как работает:
 * <ol>
 *   <li>{@code ProviderConfig} создаёт роутер с настройками из {@code gateway.routing} (application.yml) —
 *       это «настройки из конфига», запоминаем их в {@link #configDefault}.</li>
 *   <li>При создании этого бина (до старта веб-сервера) читаем строку из таблицы routing_settings.
 *       Есть — применяем её поверх конфига. Нет — остаётся конфиг.</li>
 *   <li>Сохранённый primary больше не годится (провайдер убрали из конфига или у него нет api-key) —
 *       WARN и primary из конфига (порядок из конфига). Строку в БД НЕ трогаем: вернётся ключ —
 *       после рестарта снова будет выбранный primary.</li>
 *   <li>PUT /status/routing → {@link #update}: сначала роутер (он валидирует primary), потом БД.</li>
 *   <li>DELETE /status/routing → {@link #reset}: удаляем строку, возвращаем настройки из конфига.</li>
 * </ol>
 *
 * <p>Временная недоступность primary (cooldown, исчерпан лимит) на выбор не влияет: primary остаётся
 * выбранным и сохранённым, роутер просто пропускает его, пока он недоступен, и сам возвращается к нему,
 * когда пауза/лимит закончились (порядок пересчитывается на каждый запрос).
 */
@Service
public class RoutingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(RoutingSettingsService.class);

    /** Откуда взяты текущие настройки. */
    public enum Source { CONFIG, DB }

    private final ProviderRouter router;
    private final RoutingSettingsRepository repository;
    private final Clock clock;
    private final RoutingSettings configDefault;
    private volatile Source source = Source.CONFIG;
    /** Предупреждение для дашборда: сохранённые настройки применить целиком не удалось. */
    private volatile String warning;

    public RoutingSettingsService(ProviderRouter router, RoutingSettingsRepository repository, Clock clock) {
        this.router = router;
        this.repository = repository;
        this.clock = clock;
        this.configDefault = router.routing();
        load();
    }

    /** Применить сохранённые настройки (если есть). Вызывается при старте. */
    final synchronized void load() {
        var saved = repository.findById(RoutingSettingsRecord.SINGLETON_ID);
        if (saved.isEmpty()) {
            log.info("Режим роутинга: в БД не сохранён, используем gateway.routing из конфига: {}", configDefault);
            return;
        }
        RoutingSettingsRecord rec = saved.get();
        RoutingMode mode = RoutingMode.parse(rec.getMode());
        if (mode == null) {
            warning = "Сохранённый режим «" + rec.getMode() + "» не распознан — используются настройки из конфига";
            log.warn("Режим роутинга из БД не распознан ({}), используем конфиг: {}", rec.getMode(), configDefault);
            return;
        }
        String primary = rec.getPrimaryProvider();
        if (primary != null && !router.names().contains(primary)) {
            warning = "Сохранённый primary «" + primary + "» недоступен (не настроен или нет api-key) — "
                    + "используется порядок из конфига";
            log.warn("Сохранённый primary={} недоступен (провайдер не настроен или без api-key) — "
                    + "используем порядок из конфига (primary={})", primary, configDefault.primary());
            primary = configDefault.primary();
        }
        router.setRouting(new RoutingSettings(mode, primary));
        source = Source.DB;
        log.info("Режим роутинга загружен из БД (сохранён {}): {}", rec.getUpdatedAt(), router.routing());
    }

    /**
     * Сменить режим и сохранить в БД. Неизвестный/выключенный primary — {@link IllegalArgumentException},
     * ничего не меняется. Ошибка БД — откатываем роутер и пробрасываем.
     */
    public synchronized RoutingSettings update(RoutingSettings next) {
        RoutingSettings prev = router.routing();
        router.setRouting(next);
        try {
            repository.save(new RoutingSettingsRecord(next.mode().name(), next.primary(), clock.instant()));
        } catch (RuntimeException e) {
            router.setRouting(prev);
            throw e;
        }
        source = Source.DB;
        warning = null;
        return router.routing();
    }

    /** Удалить сохранённые настройки и вернуть gateway.routing из конфига. */
    public synchronized RoutingSettings reset() {
        repository.deleteAll();
        router.setRouting(configDefault);
        source = Source.CONFIG;
        warning = null;
        log.info("Режим роутинга сброшен к настройкам из конфига: {}", configDefault);
        return configDefault;
    }

    public Source source() {
        return source;
    }

    public String warning() {
        return warning;
    }

    public RoutingSettings configDefault() {
        return configDefault;
    }
}
