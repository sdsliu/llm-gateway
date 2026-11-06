package ru.akiev.gateway.provider.mock;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Пишет сырое тело сценария {@code bad-json}, не прогоняя его через Jackson. */
@RestControllerAdvice
@Profile({"mock", "test"})
public class MockRawBodyAdvice {

    @ExceptionHandler(RawMockBody.class)
    public void write(RawMockBody e, HttpServletResponse response) throws IOException {
        response.setStatus(e.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] bytes = e.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
