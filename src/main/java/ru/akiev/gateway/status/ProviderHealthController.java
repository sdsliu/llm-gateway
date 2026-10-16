package ru.akiev.gateway.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.akiev.gateway.api.ChatController;
import ru.akiev.gateway.status.ProviderHealthService.ModelsCache;
import ru.akiev.gateway.status.ProviderHealthService.PingResult;

/** Ping провайдеров и списки моделей для дашборда. */
@RestController
@RequestMapping("/status")
public class ProviderHealthController {

    private final ProviderHealthService health;
    private final ChatController chat;
    private final StatusBroadcaster broadcaster;

    public ProviderHealthController(ProviderHealthService health, ChatController chat, StatusBroadcaster broadcaster) {
        this.health = health;
        this.chat = chat;
        this.broadcaster = broadcaster;
    }

    @PostMapping("/providers/{name}/ping")
    public PingResult ping(@PathVariable String name) {
        check(name);
        PingResult r = health.ping(name);
        broadcaster.broadcastSnapshot();
        return r;
    }

    @PostMapping("/providers/ping")
    public List<PingResult> pingAll() {
        List<PingResult> r = health.pingAll();
        broadcaster.broadcastSnapshot();
        return r;
    }

    @GetMapping("/providers/{name}/models")
    public ModelsCache models(@PathVariable String name, @RequestParam(defaultValue = "false") boolean refresh) {
        check(name);
        return health.models(name, refresh);
    }

    /** gateway — то, что отдаёт /v1/models; providers — реальные /models каждого провайдера (кэш). */
    @GetMapping("/models")
    public Map<String, Object> allModels(@RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gateway", chat.models().data());
        m.put("providers", health.allModels(refresh));
        return m;
    }

    private void check(String name) {
        if (!health.exists(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный провайдер: " + name);
        }
    }
}
