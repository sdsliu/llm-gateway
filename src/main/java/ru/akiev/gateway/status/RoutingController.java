package ru.akiev.gateway.status;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.akiev.gateway.dto.ErrorResponse;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/**
 * Режим роутинга для дашборда. Изменения живут только в памяти (после рестарта — снова
 * gateway.routing из application.yml). Как и остальной /status, эндпоинт без авторизации:
 * шлюз рассчитан на локальный запуск, наружу порт не публикуем.
 */
@RestController
@RequestMapping("/status/routing")
public class RoutingController {

    /** Тело PUT: mode обязателен; primary пустой/null — без primary. */
    public record RoutingUpdate(String mode, String primary) {
    }

    private final ProviderRouter router;
    private final StatusService statusService;
    private final StatusBroadcaster broadcaster;

    public RoutingController(ProviderRouter router, StatusService statusService, StatusBroadcaster broadcaster) {
        this.router = router;
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
            router.setRouting(new RoutingSettings(mode, body.primary()));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        // Все открытые дашборды сразу видят новый режим через SSE.
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(statusService.routing());
    }

    private static ResponseEntity<ErrorResponse> bad(String msg) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(msg, "invalid_request_error", null));
    }
}
