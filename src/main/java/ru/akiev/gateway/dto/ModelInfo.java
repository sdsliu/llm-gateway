package ru.akiev.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Элемент списка /v1/models. */
public record ModelInfo(String id, String object, Long created, @JsonProperty("owned_by") String ownedBy) {
    public static ModelInfo of(String id, String ownedBy) {
        return new ModelInfo(id, "model", 0L, ownedBy);
    }
}
