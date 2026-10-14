package ru.akiev.gateway.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final ProviderRouter router;

    public ChatController(ProviderRouter router) {
        this.router = router;
    }

    @PostMapping("/chat/completions")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return router.chat(request);
    }

    @GetMapping("/models")
    public ModelList models() {
        return ModelList.of(router.providers().stream()
                .flatMap(p -> p.models().stream().map(m -> ModelInfo.of(m, p.name())))
                .distinct()
                .toList());
    }
}
