package ru.akiev.gateway.dto;

/** Ошибка в формате OpenAI: {"error": {"message", "type", "code"}}. */
public record ErrorResponse(Error error) {
    public record Error(String message, String type, String code) {
    }

    public static ErrorResponse of(String message, String type, String code) {
        return new ErrorResponse(new Error(message, type, code));
    }
}
