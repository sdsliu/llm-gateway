package ru.akiev.gateway.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Choice;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    LlmProvider provider;

    @Test
    void chatCompletionReturnsProviderResponse() throws Exception {
        when(provider.chat(any())).thenReturn(new ChatResponse("chatcmpl-1", "chat.completion", 1L, "m",
                List.of(new Choice(0, new Message("assistant", "Привет!"), "stop")), new Usage(3, 2, 5)));

        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/coder\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"unknown\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Привет!"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").value(5));
    }

    @Test
    void emptyMessagesGives400() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"x\",\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
        verify(provider, never()).chat(any());
    }

    @Test
    void malformedJsonGives400() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerErrorIsPropagated() throws Exception {
        when(provider.chat(any())).thenThrow(new ProviderException(504, "timeout", null));
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.type").value("provider_error"));
    }

    @Test
    void modelsListed() throws Exception {
        when(provider.models()).thenReturn(List.of("llama-3.3-70b-versatile"));
        when(provider.name()).thenReturn("groq");
        mvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("llama-3.3-70b-versatile"))
                .andExpect(jsonPath("$.data[0].owned_by").value("groq"));
    }
}
