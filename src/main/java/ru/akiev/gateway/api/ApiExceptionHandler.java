package ru.akiev.gateway.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.akiev.gateway.dto.ErrorResponse;
import ru.akiev.gateway.provider.ProviderException;
import ru.akiev.gateway.router.AllProvidersUnavailableException;
import ru.akiev.gateway.router.RequestTooLargeException;

/** Ошибки в формате OpenAI. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return ResponseEntity.badRequest().body(ErrorResponse.of(msg, "invalid_request_error", null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("Malformed JSON body", "invalid_request_error", null));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> provider(ProviderException e) {
        return ResponseEntity.status(e.status())
                .body(ErrorResponse.of(e.getMessage(), "provider_error", String.valueOf(e.status())));
    }

    @ExceptionHandler(AllProvidersUnavailableException.class)
    public ResponseEntity<ErrorResponse> allDown(AllProvidersUnavailableException e) {
        return ResponseEntity.status(503)
                .body(ErrorResponse.of(e.getMessage(), "service_unavailable", "all_providers_unavailable"));
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public ResponseEntity<ErrorResponse> tooLarge(RequestTooLargeException e) {
        return ResponseEntity.status(413)
                .body(ErrorResponse.of(e.getMessage(), "request_too_large", "request_too_large"));
    }
}
