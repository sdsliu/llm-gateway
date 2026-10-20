package ru.akiev.gateway.status;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.akiev.gateway.api.generated.api.StatusApi;

/**
 * Состояние шлюза. {@code GET /status} реализует сгенерированный из контракта {@link StatusApi}.
 *
 * <p>{@code GET /status/stream} (SSE) написан вручную и в контракте помечен {@code x-internal: true}:
 * генератор не умеет тип ответа {@link SseEmitter} — он сгенерировал бы {@code ResponseEntity<String>},
 * а SSE-соединение должно оставаться открытым и получать события {@code snapshot}/{@code heartbeat}.
 */
@RestController
public class StatusController implements StatusApi {

    private final StatusService statusService;
    private final StatusBroadcaster broadcaster;

    public StatusController(StatusService statusService, StatusBroadcaster broadcaster) {
        this.statusService = statusService;
        this.broadcaster = broadcaster;
    }

    /** Разовый срез состояния (JSON) — для curl и будущего Telegram-бота. */
    @Override
    public ResponseEntity<StatusSnapshot> getStatus() {
        return ResponseEntity.ok(statusService.snapshot());
    }

    /** Живой поток обновлений для дашборда (SSE). Вручную — см. комментарий к классу. */
    @GetMapping(value = "/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
