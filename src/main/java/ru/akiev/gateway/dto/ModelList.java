package ru.akiev.gateway.dto;

import java.util.List;

public record ModelList(String object, List<ModelInfo> data) {
    public static ModelList of(List<ModelInfo> data) {
        return new ModelList("list", data);
    }
}
