package ru.akiev.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(
        Integer index,
        Message message,
        @JsonProperty("finish_reason") String finishReason) {
}
