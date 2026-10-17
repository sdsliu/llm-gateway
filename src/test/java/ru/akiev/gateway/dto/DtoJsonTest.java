package ru.akiev.gateway.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonTest
class DtoJsonTest {

    @Autowired
    ObjectMapper mapper;

    @Test
    void deserializesRequestWithSnakeCaseAndUnknownFields() throws Exception {
        ChatRequest r = mapper.readValue("""
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "max_tokens":50,"top_p":0.9,"stream":false,"tools":[],"seed":1}
                """, ChatRequest.class);
        assertThat(r.maxTokens()).isEqualTo(50);
        assertThat(r.topP()).isEqualTo(0.9);
        assertThat(r.messages()).hasSize(1);
    }

    @Test
    void serializesResponseSnakeCaseWithoutNulls() throws Exception {
        var resp = new ChatResponse("id", "chat.completion", 1L, "m",
                List.of(new Choice(0, new Message("assistant", "ok"), "stop")), new Usage(1, 2, 3));
        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"finish_reason\":\"stop\"")
                .contains("\"prompt_tokens\":1").contains("\"completion_tokens\":2").contains("\"total_tokens\":3");

        String req = mapper.writeValueAsString(new ChatRequest("m", List.of(new Message("user", "x")),
                null, null, null, null, null, null, null, null));
        assertThat(req).doesNotContain("null").doesNotContain("temperature");
    }

    @Test
    void reasoningEffortIsPassedThroughAndSurvivesCopies() throws Exception {
        ChatRequest r = mapper.readValue("""
                {"model":"m","messages":[{"role":"user","content":"hi"}],"reasoning_effort":"low"}
                """, ChatRequest.class);
        assertThat(r.reasoningEffort()).isEqualTo("low");
        assertThat(r.withModel("x").withStream(true).withoutStream().reasoningEffort()).isEqualTo("low");
        assertThat(mapper.writeValueAsString(r.withModel("provider-model"))).contains("\"reasoning_effort\":\"low\"");
        String none = mapper.writeValueAsString(new ChatRequest("m", List.of(new Message("user", "x")),
                null, null, null, null, null, null, null, null));
        assertThat(none).doesNotContain("reasoning_effort");
    }
}
