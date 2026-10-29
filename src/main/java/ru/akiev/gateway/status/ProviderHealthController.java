package ru.akiev.gateway.status;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.akiev.gateway.api.ChatController;
import ru.akiev.gateway.api.generated.api.ProvidersApi;
import ru.akiev.gateway.api.generated.model.AllModels;
import ru.akiev.gateway.status.ProviderHealthService.ModelsCache;
import ru.akiev.gateway.status.ProviderHealthService.PingResult;

/**
 * Ping провайдеров и списки моделей для дашборда.
 * API first: пути/параметры — из сгенерированного {@link ProvidersApi}, ответ /status/models — сгенерированный
 * {@link AllModels} (раньше был {@code Map<String,Object>} с теми же ключами gateway и providers).
 */
@RestController
public class ProviderHealthController implements ProvidersApi {

    private final ProviderHealthService health;
    private final ChatController chat;
    private final StatusBroadcaster broadcaster;

    public ProviderHealthController(ProviderHealthService health, ChatController chat, StatusBroadcaster broadcaster) {
        this.health = health;
        this.chat = chat;
        this.broadcaster = broadcaster;
    }

    @Override
    public ResponseEntity<PingResult> pingProvider(String name) {
        check(name);
        PingResult r = health.ping(name);
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(r);
    }

    @Override
    public ResponseEntity<List<PingResult>> pingAllProviders() {
        List<PingResult> r = health.pingAll();
        broadcaster.broadcastSnapshot();
        return ResponseEntity.ok(r);
    }

    @Override
    public ResponseEntity<ModelsCache> getProviderModels(String name, Boolean refresh) {
        check(name);
        return ResponseEntity.ok(health.models(name, Boolean.TRUE.equals(refresh)));
    }

    /** gateway — то, что отдаёт /v1/models; providers — реальные /models каждого провайдера (кэш). */
    @Override
    public ResponseEntity<AllModels> getAllModels(Boolean refresh) {
        return ResponseEntity.ok(new AllModels()
                .gateway(chat.models())
                .providers(health.allModels(Boolean.TRUE.equals(refresh))));
    }

    private void check(String name) {
        if (!health.exists(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный провайдер: " + name);
        }
    }
}
