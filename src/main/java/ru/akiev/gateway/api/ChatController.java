package ru.akiev.gateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.akiev.gateway.dto.ErrorResponse;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.router.StreamSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import ru.akiev.gateway.usage.RequestTags;
import org.springframework.web.bind.annotation.RestController;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import ru.akiev.gateway.api.generated.api.OpenaiApi;
import ru.akiev.gateway.api.generated.model.ModelInfo;
import ru.akiev.gateway.api.generated.model.ModelList;
import ru.akiev.gateway.router.ProviderRouter;

/**
 * OpenAI-совместимые эндпоинты шлюза.
 *
 * <p><b>API first.</b> Контракт — {@code src/main/resources/openapi/llm-gateway.yaml}. Класс реализует
 * сгенерированный из него интерфейс {@link OpenaiApi}: путь, метод, параметры и тип ответа {@code GET /v1/models}
 * берутся из интерфейса (аннотации {@code @RequestMapping} наследуются от него), поэтому здесь их нет.
 *
 * <p>{@code POST /v1/chat/completions} — исключение, он написан вручную (в контракте помечен {@code x-internal: true},
 * генератор для него метод не создаёт). Причины:
 * <ul>
 *   <li>один и тот же эндпоинт отдаёт либо JSON ({@code stream=false}), либо поток {@code text/event-stream}
 *       ({@code stream=true}); генератор умеет только один Java-тип ответа ({@code ResponseEntity<ChatResponse>}),
 *       а для стрима нужен {@link StreamingResponseBody} — поэтому метод возвращает {@code Object};</li>
 *   <li>тело запроса — внутренний {@link ChatRequest} со своей логикой (streamRequested, withModel, ...), его
 *       нельзя заменить сгенерированным POJO без риска изменить то, что уходит провайдеру.</li>
 * </ul>
 * Соответствие ручного метода контракту (путь и метод есть в YAML) проверяет {@code ContractMappingTest}.
 */
@RestController
public class ChatController implements OpenaiApi {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ProviderRouter router;
    private final ObjectMapper mapper;

    public ChatController(ProviderRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    /**
     * Возвращаемый тип Object: Spring MVC выбирает обработчик по фактическому значению.
     * stream=false → ChatResponse (JSON, как раньше);
     * stream=true  → StreamingResponseBody (text/event-stream в формате OpenAI).
     *
     * <p>Заголовки x-run-id / x-issue / x-client и поле user из тела привязываются к потоку
     * ({@link RequestTags}) — так каждая попытка в usage_record получает метки прогона.
     */
    @PostMapping("/v1/chat/completions")
    public Object chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response,
            @RequestHeader(value = "x-run-id", required = false) String runId,
            @RequestHeader(value = "x-issue", required = false) String issue,
            @RequestHeader(value = "x-client", required = false) String client) {
        RequestTags tags = new RequestTags(runId, issue, client, request.user());
        if (!request.streamRequested()) {
            try (var scope = RequestTags.bind(tags)) {
                return router.chat(request);
            }
        }
        // Failover происходит ЗДЕСЬ, синхронно, до того как мы начали ответ.
        // Если все провайдеры недоступны — исключение уйдёт в ApiExceptionHandler → 503 JSON.
        StreamSession session;
        try (var scope = RequestTags.bind(tags)) {
            session = router.openStream(request);
        }

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // nginx: не буферизовать SSE

        // StreamingResponseBody выполняется в отдельном потоке (MVC async), поток Tomcat свободен.
        // Формат OpenAI: "data: {json}\n\n" на каждый чанк и "data: [DONE]\n\n" в конце.
        return (StreamingResponseBody) out -> {
            // Другой поток (MVC async): метки нужно привязать заново, иначе итог стрима запишется без них
            try (var scope = RequestTags.bind(tags); session) {
                session.drainTo(json -> write(out, json));
            } catch (ProviderException e) {
                // Ошибка посреди стрима: ответ уже начат (200), поэтому сообщаем об ошибке
                // error-чанком — так делают и OpenAI-совместимые провайдеры.
                log.warn("Стрим от {} оборвался: {}", session.provider().name(), e.getMessage());
                write(out, errorJson(e));
            }
            write(out, "[DONE]");
        };
    }

    private static void write(OutputStream out, String data) throws java.io.IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        // flush после каждого чанка — иначе Tomcat накопит буфер и клиент увидит текст «пачками».
        out.flush();
    }

    private String errorJson(ProviderException e) {
        try {
            return mapper.writeValueAsString(ErrorResponse.of(e.getMessage(), "provider_error", String.valueOf(e.status())));
        } catch (JsonProcessingException ex) {
            return "{\"error\":{\"message\":\"stream failed\"}}";
        }
    }

    /**
     * GET /v1/models — реализация метода сгенерированного интерфейса. Параметр {@code authorization}
     * объявлен в контракте ради совместимости с OpenAI SDK; шлюз его не проверяет.
     */
    @Override
    public ResponseEntity<ModelList> listModels(String authorization) {
        return ResponseEntity.ok(new ModelList()._object("list").data(models()));
    }

    /** Модели всех провайдеров без дублей (используется и в GET /status/models). */
    public List<ModelInfo> models() {
        return router.providers().stream()
                .flatMap(p -> p.models().stream()
                        .map(m -> new ModelInfo().id(m)._object("model").created(0L).ownedBy(p.name())))
                .distinct() // сгенерированные модели реализуют equals/hashCode по всем полям, как record раньше
                .toList();
    }
}
