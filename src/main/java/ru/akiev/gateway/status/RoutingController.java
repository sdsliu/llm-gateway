package ru.akiev.gateway.status;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.akiev.gateway.dto.ErrorResponse;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/**
 * Режим роутинга для дашборда. PUT сохраняет выбор в БД (таблица routing_settings) — он переживает
 * рестарт; DELETE удаляет сохранённое и возвращает gateway.routing из application.yml.
 * Как и остальной /status, эндпоинт без авторизации: шлюз рассчитан на локальный запуск,
 * наружу порт не публикуем.
 */
@RestController
@RequestMapping("/status/routing")
public class RoutingController {

    /** Тело PUT: mode обязателен; primary пустой/null — без primary. */
    public record RoutingUpdate(String mode, String primary) {
    }

    private final RoutingSettingsService settings;
    private final StatusService statusService;
    private final StatusBroadcaster broadcaster;

    public RoutingController(RoutingSettingsService settings, StatusService statusService,
            StatusBroadcaster broadcaster) {
        this.settings = settings;
        this.statusService = statusService;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public RoutingInfo get() {
        return statusService.routing();
    }

    @PutMapping
    public ResponseEntity<?> put(@RequestBody RoutingUpdate body) {
        RoutingMode mode = RoutingMode.parse(body.mode());
        if (mode == null) {
            return bad("mode must be one of PRIORITY, ROUND_ROBIN (got: " + body.mode() + ")");
        }
        try {
            settings.update(new RoutingSettings(mode, body.primary()));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        // Все открытые дашборды сразу видят новый режим через SSE.
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(statusService.routing());
    }

    /** «Сбросить к настройкам из конфига»: удалить сохранённое в БД, вернуть gateway.routing. */
    @DeleteMapping
    public RoutingInfo reset() {
        settings.reset();
        broadcaster.broadcastSnapshot();
        return statusService.routing();
    }

    private static ResponseEntity<ErrorResponse> bad(String msg) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(msg, "invalid_request_error", null));
    }
}
