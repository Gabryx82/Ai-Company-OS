package com.aicompany.backend.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only (PHASE 32, TD-47): every JSON response a test receives is checked
 * against the committed contract, {@code docs/api/openapi.json} -- a required
 * field missing, or {@code null} where the contract says it never is, is
 * recorded. {@code ResponseContractTest} runs last and asserts there is none.
 * Registered for every context through the test {@code AutoConfiguration.imports}.
 */
@AutoConfiguration
public class ResponseContractRecorder {

    private static final String CONTRACT = "../docs/api/openapi.json";
    private static final Set<String> VIOLATIONS = new ConcurrentSkipListSet<>();
    private static final AtomicInteger CHECKED = new AtomicInteger();
    private static volatile JsonNode contract;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Boolean> UNKNOWN_ROUTES = new ConcurrentHashMap<>();

    public static Set<String> violations() {
        return VIOLATIONS;
    }

    public static int checked() {
        return CHECKED.get();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> responseContractFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
                try {
                    chain.doFilter(request, wrapper);
                } finally {
                    check(request, wrapper);
                    wrapper.copyBodyToResponse();
                }
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static void check(HttpServletRequest request, ContentCachingResponseWrapper response) {
        String type = response.getContentType();
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (type == null || !type.startsWith("application/json") || pattern == null
                || response.getStatus() < 200 || response.getStatus() >= 300 || response.getContentSize() == 0) {
            return;
        }
        JsonNode schema = responseSchema(request.getMethod().toLowerCase(), pattern.toString(), response.getStatus());
        if (schema == null) {
            return;
        }
        try {
            JsonNode body = JSON.readTree(response.getContentAsByteArray());
            CHECKED.incrementAndGet();
            walk(body, schema, request.getMethod() + " " + pattern, request.getMethod() + " " + pattern);
        } catch (RuntimeException unreadable) {
            VIOLATIONS.add(request.getMethod() + " " + pattern + ": the body is not JSON");
        }
    }

    private static JsonNode responseSchema(String method, String pattern, int status) {
        JsonNode operation = contract().path("paths").path(pattern).path(method);
        if (operation.isMissingNode()) {
            UNKNOWN_ROUTES.put(method + " " + pattern, true);
            return null;
        }
        JsonNode responses = operation.path("responses");
        JsonNode response = responses.has(String.valueOf(status)) ? responses.path(String.valueOf(status))
                : responses.path("default");
        for (JsonNode media : response.path("content")) {
            if (media.has("schema")) {
                return media.path("schema");
            }
        }
        return null;
    }

    /** Where a value breaks its schema: {@code Schema.property}, with the first route that showed it. */
    private static void walk(JsonNode value, JsonNode schema, String where, String route) {
        if (schema.has("$ref")) {
            String name = schema.path("$ref").asString().replace("#/components/schemas/", "");
            walk(value, contract().path("components").path("schemas").path(name), name, route);
            return;
        }
        if (schema.has("anyOf")) {
            if (value.isNull()) {
                return;
            }
            for (JsonNode option : schema.path("anyOf")) {
                if (!isNullType(option)) {
                    walk(value, option, where, route);
                }
            }
            return;
        }
        if (value.isArray() && schema.has("items")) {
            value.forEach(item -> walk(item, schema.path("items"), where + "[]", route));
            return;
        }
        if (!value.isObject()) {
            return;
        }
        JsonNode properties = schema.path("properties");
        for (JsonNode required : schema.path("required")) {
            String name = required.asString();
            if (!value.has(name)) {
                VIOLATIONS.add(where + "." + name + " is required and missing (" + route + ")");
            }
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            JsonNode property = properties.path(field.getKey());
            if (property.isMissingNode()) {
                continue;
            }
            if (field.getValue().isNull()) {
                boolean required = false;
                for (JsonNode r : schema.path("required")) {
                    required |= r.asString().equals(field.getKey());
                }
                if (required && !allowsNull(property)) {
                    VIOLATIONS.add(where + "." + field.getKey() + " is null but not nullable (" + route + ")");
                }
                continue;
            }
            walk(field.getValue(), property, where + "." + field.getKey(), route);
        }
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject() && properties.isMissingNode()) {
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (!entry.getValue().isNull()) {
                    walk(entry.getValue(), additional, where + "{}", route);
                }
            }
        }
    }

    private static boolean allowsNull(JsonNode property) {
        if (isNullType(property)) {
            return true;
        }
        for (JsonNode option : property.path("anyOf")) {
            if (isNullType(option)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isString()) {
            return type.asString().equals("null");
        }
        for (JsonNode t : type) {
            if (t.asString().equals("null")) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode contract() {
        JsonNode loaded = contract;
        if (loaded == null) {
            try {
                loaded = JSON.readTree(Files.readString(Path.of(CONTRACT)));
            } catch (IOException missing) {
                throw new IllegalStateException("the committed contract cannot be read at " + CONTRACT, missing);
            }
            contract = loaded;
        }
        return loaded;
    }
}
