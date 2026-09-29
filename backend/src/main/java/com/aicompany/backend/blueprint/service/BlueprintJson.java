package com.aicompany.backend.blueprint.service;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON columns of the Blueprint, read and written in one place. A column that
 * cannot be read is treated as empty rather than failing the page: it is derived
 * or re-editable data, and a broken block must not hide the others.
 */
final class BlueprintJson {

    /** What the last scan read for the fields an operator can override. */
    record Detected(String name, NodeKind kind, String description, String pseudocode) {
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private BlueprintJson() {
    }

    static String write(Object value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }

    static CodeStructure structure(String text) {
        if (text == null || text.isBlank()) {
            return CodeStructure.empty();
        }
        try {
            return JSON.readValue(text, CodeStructure.class).normalized();
        } catch (RuntimeException unreadable) {
            return CodeStructure.empty();
        }
    }

    static Detected detected(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(text, Detected.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    static List<String> tags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<String>>() { });
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    static Map<String, BlueprintViews.BlueprintPoint> positions(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.readValue(text, new TypeReference<Map<String, BlueprintViews.BlueprintPoint>>() { }));
        } catch (RuntimeException unreadable) {
            return new LinkedHashMap<>();
        }
    }
}
