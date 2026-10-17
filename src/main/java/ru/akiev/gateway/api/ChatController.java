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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import ru.akiev.gateway.usage.RequestTags;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.ModelInfo;
import ru.akiev.gateway.dto.ModelList;
import ru.akiev.gateway.router.ProviderRouter;

/** OpenAI-совместимые эндпоинты шлюза. */
@RestController
@RequestMapping("/v1")
public class ChatController {

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
    @PostMapping("/chat/completions")
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

    @GetMapping("/models")
    public ModelList models() {
        return ModelList.of(router.providers().stream()
                .flatMap(p -> p.models().stream().map(m -> ModelInfo.of(m, p.name())))
                .distinct()
                .toList());
    }
}
