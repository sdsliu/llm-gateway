package ru.akiev.gateway.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.akiev.gateway.usage.UsageRecordedEvent;

/**
 * Рассылка обновлений дашборду через Server-Sent Events (SSE).
 *
 * <h2>SSE или WebSocket?</h2>
 * <ul>
 *   <li><b>SSE</b> — обычный HTTP-ответ с Content-Type: text/event-stream, который сервер
 *       не закрывает и дописывает в него события вида {@code event: snapshot\ndata: {...}\n\n}.
 *       Поток данных — только сервер → клиент.</li>
 *   <li><b>WebSocket</b> — отдельный протокол (upgrade с HTTP), полнодуплексный: обе стороны шлют
 *       сообщения. Нужен для чатов, игр, совместного редактирования.</li>
 * </ul>
 * Дашборду нужно только получать обновления, слать на сервер ему нечего — поэтому SSE подходит лучше:
 * <ul>
 *   <li>в браузере есть готовый {@code EventSource} с <b>автоматическим переподключением</b>
 *       (WebSocket-переподключение пришлось бы писать руками);</li>
 *   <li>это обычный HTTP: проходит через прокси, работает с тем же портом и Spring MVC,
 *       не нужны доп. зависимости (spring-websocket, STOMP);</li>
 *   <li>тот же механизм (text/event-stream) мы будем использовать на этапе 4 для стриминга
 *       ответов LLM в формате OpenAI — хорошая тренировка.</li>
 * </ul>
 *
 * <h2>Как это устроено в Spring MVC</h2>
 * {@link SseEmitter} — «открытый» ответ: контроллер возвращает его, поток Tomcat освобождается
 * (асинхронный Servlet), а мы позже вызываем {@code emitter.send(...)} из любого потока.
 * На этапе 4 (WebFlux) аналогом будет {@code Flux<ServerSentEvent<T>>}.
 *
 * <p>Когда шлём:
 * <ul>
 *   <li>при подключении — полный snapshot;</li>
 *   <li>после каждой записи usage_record (событие {@link UsageRecordedEvent});</li>
 *   <li>при смене состояния провайдера (healthy ↔ cooldown/limit) — проверяем раз в секунду,
 *       т.к. окончание cooldown — это просто момент времени, а не событие;</li>
 *   <li>heartbeat раз в ~15 с — чтобы прокси не закрывали «тихое» соединение и чтобы быстро
 *       обнаружить отвалившихся клиентов (send упадёт с IOException).</li>
 * </ul>
 */
@Component
public class StatusBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StatusBroadcaster.class);

    private final StatusService statusService;
    private final long emitterTimeoutMs;
    /** CopyOnWriteArrayList — безопасно перебирать, пока другие потоки добавляют/удаляют клиентов. */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile Map<String, String> lastStates = Map.of();

    public StatusBroadcaster(StatusService statusService,
            @Value("${gateway.status.emitter-timeout:30m}") Duration emitterTimeout) {
        this.statusService = statusService;
        this.emitterTimeoutMs = emitterTimeout.toMillis();
    }

    public SseEmitter subscribe() {
        // По таймауту соединение закроется, EventSource в браузере сам переподключится.
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            // reconnectTime — поле "retry:" в SSE: через сколько мс браузеру переподключаться.
            emitter.send(SseEmitter.event().reconnectTime(3000).name("snapshot")
                    .data(statusService.snapshot(), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public int clients() {
        return emitters.size();
    }

    @EventListener
    public void onUsage(UsageRecordedEvent event) {
        broadcastSnapshot();
    }

    @Scheduled(fixedDelayString = "${gateway.status.state-check:PT1S}")
    public void checkStateChanges() {
        Map<String, String> now = statusService.providerStates();
        if (!now.equals(lastStates)) {
            log.info("Состояние провайдеров: {} → {}", lastStates, now);
            lastStates = now;
            broadcastSnapshot();
        }
    }

    @Scheduled(fixedRateString = "${gateway.status.heartbeat:PT15S}")
    public void heartbeat() {
        send(SseEmitter.event().name("heartbeat").data(Instant.now().toString()));
    }

    public void broadcastSnapshot() {
        if (emitters.isEmpty()) {
            return;
        }
        send(SseEmitter.event().name("snapshot").data(statusService.snapshot(), MediaType.APPLICATION_JSON));
    }

    private void send(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                // Клиент ушёл (закрыл вкладку) — убираем его.
                emitters.remove(emitter);
            }
        }
    }
}
