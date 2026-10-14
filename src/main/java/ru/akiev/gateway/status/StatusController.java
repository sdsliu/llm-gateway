package ru.akiev.gateway.status;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StatusController {

    private final StatusService statusService;
    private final StatusBroadcaster broadcaster;

    public StatusController(StatusService statusService, StatusBroadcaster broadcaster) {
        this.statusService = statusService;
        this.broadcaster = broadcaster;
    }

    /** Разовый срез состояния (JSON) — для curl и будущего Telegram-бота. */
    @GetMapping("/status")
    public StatusSnapshot status() {
        return statusService.snapshot();
    }

    /** Живой поток обновлений для дашборда (SSE). */
    @GetMapping(value = "/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
