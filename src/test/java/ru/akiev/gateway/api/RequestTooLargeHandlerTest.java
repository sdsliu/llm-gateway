package ru.akiev.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.akiev.gateway.router.RequestTooLargeException;

class RequestTooLargeHandlerTest {
    @Test
    void mapsTo413RequestTooLarge() {
        var r = new ApiExceptionHandler().tooLarge(new RequestTooLargeException("Request too large for all available providers (groq)."));
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(r.getBody().error().type()).isEqualTo("request_too_large");
        assertThat(r.getBody().error().code()).isEqualTo("request_too_large");
        assertThat(r.getBody().error().message()).contains("groq");
    }
}
