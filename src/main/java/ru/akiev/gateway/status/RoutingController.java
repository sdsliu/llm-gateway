package ru.akiev.gateway.status;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.akiev.gateway.api.InvalidRequestException;
import ru.akiev.gateway.api.generated.api.RoutingApi;
import ru.akiev.gateway.api.generated.model.RoutingUpdate;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/**
 * Режим роутинга для дашборда. PUT сохраняет выбор в БД (таблица routing_settings) — он переживает
 * рестарт; DELETE удаляет сохранённое и возвращает gateway.routing из application.yml.
 * Как и остальной /status, эндпоинт без авторизации: шлюз рассчитан на локальный запуск,
 * наружу порт не публикуем.
 *
 * <p><b>API first:</b> пути и методы приходят из сгенерированного {@link RoutingApi}; тело PUT —
 * сгенерированный {@link RoutingUpdate} (mode обязателен по смыслу; primary пустой/null — без primary).
 */
@RestController
public class RoutingController implements RoutingApi {

    private final RoutingSettingsService settings;
    private final StatusService statusService;
    private final StatusBroadcaster broadcaster;

    public RoutingController(RoutingSettingsService settings, StatusService statusService,
            StatusBroadcaster broadcaster) {
        this.settings = settings;
        this.statusService = statusService;
        this.broadcaster = broadcaster;
    }

    @Override
    public ResponseEntity<RoutingInfo> getRouting() {
        return ResponseEntity.ok(statusService.routing());
    }

    /**
     * Интерфейс требует тип ответа {@code ResponseEntity<RoutingInfo>}, поэтому ошибку 400 нельзя вернуть
     * телом прямо отсюда (раньше было {@code ResponseEntity<?>}). Бросаем {@link InvalidRequestException} —
     * ApiExceptionHandler превращает его в тот же самый ответ: 400 и
     * {@code {"error":{"message":..., "type":"invalid_request_error"}}}.
     */
    @Override
    public ResponseEntity<RoutingInfo> updateRouting(RoutingUpdate body) {
        RoutingMode mode = RoutingMode.parse(body.getMode());
        if (mode == null) {
            throw new InvalidRequestException("mode must be one of PRIORITY, ROUND_ROBIN, HEADROOM (got: " + body.getMode() + ")");
        }
        try {
            settings.update(new RoutingSettings(mode, body.getPrimary()));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        // Все открытые дашборды сразу видят новый режим через SSE.
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(statusService.routing());
    }

    /** «Сбросить к настройкам из конфига»: удалить сохранённое в БД, вернуть gateway.routing. */
    @Override
    public ResponseEntity<RoutingInfo> resetRouting() {
        settings.reset();
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(statusService.routing());
    }
}
