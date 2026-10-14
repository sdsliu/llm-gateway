package ru.akiev.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** Сообщение диалога в формате OpenAI: role = system | user | assistant | tool. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(@NotBlank String role, String content) {
}
