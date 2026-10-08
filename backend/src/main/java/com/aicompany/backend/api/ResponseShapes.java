package com.aicompany.backend.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a response always carries, and what it may carry as {@code null}, said by
 * the contract (PHASE 32, TD-47).
 *
 * <p>springdoc marks no response field as required, so every generated type was
 * all-optional and the console had to guess which nested fields could be null.
 * Here, for every schema built from a Java record and reachable only from
 * responses: every component is <em>required</em> -- a record always serialises
 * all of them, and Jackson writes nulls -- and a component is <em>nullable</em>
 * exactly when it is annotated {@link Nullable}. A primitive never is.
 *
 * <p>Shapes that are also request bodies keep springdoc's rules: a client may
 * omit an optional field, so "required" would be false there.
 *
 * <p>{@code ResponseContractTest} holds the annotations to the data: every JSON
 * response the test suite produces is checked against this contract, so a
 * {@code null} where the contract says there is none turns the suite red.
 */
final class ResponseShapes implements OpenApiCustomizer {

    private static final String SCHEMA_REF = "#/components/schemas/";

    private final Map<String, Class<?>> records;

    ResponseShapes(Map<String, Class<?>> records) {
        this.records = records;
    }

    /** Every record of the application, by simple name -- the name springdoc gives its schema (TD-59). */
    static ResponseShapes forApplication() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> "java.lang.Record".equals(reader.getClassMetadata().getSuperClassName()));
        Map<String, Class<?>> records = new HashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.aicompany.backend")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName(), false, ResponseShapes.class.getClassLoader());
                records.put(type.getSimpleName(), type);
            } catch (ClassNotFoundException | LinkageError unloadable) {
                // not a shape of the API
            }
        }
        return new ResponseShapes(records);
    }

    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null || api.getPaths() == null) {
            return;
        }
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        Set<String> requests = reachable(api, schemas, true);
        Set<String> responses = reachable(api, schemas, false);
        for (String name : responses) {
            Class<?> type = records.get(name);
            Schema<?> schema = schemas.get(name);
            if (requests.contains(name) || type == null || schema == null || schema.getProperties() == null) {
                continue;
            }
            List<String> required = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                Schema<?> property = schema.getProperties().get(component.getName());
                if (property == null) {
                    continue;
                }
                required.add(component.getName());
                if (isNullable(component)) {
                    schema.getProperties().put(component.getName(), nullable(property));
                }
            }
            schema.setRequired(required);
        }
    }

    static boolean isNullable(RecordComponent component) {
        return !component.getType().isPrimitive()
                && (component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                    || component.isAnnotationPresent(Nullable.class));
    }

    /** The same property, or {@code null}: a type list with "null" in 3.1, or a union for a reference. */
    private static Schema<?> nullable(Schema<?> property) {
        if (property.get$ref() != null) {
            JsonSchema none = new JsonSchema();
            none.setTypes(new LinkedHashSet<>(List.of("null")));
            JsonSchema union = new JsonSchema();
            union.setAnyOf(new ArrayList<>(List.of(property, none)));
            return union;
        }
        Set<String> types = property.getTypes() != null ? new LinkedHashSet<>(property.getTypes())
                : property.getType() != null ? new LinkedHashSet<>(List.of(property.getType())) : new LinkedHashSet<>();
        types.add("null");
        property.setTypes(types);
        return property;
    }

    /** The component schemas reached, directly or through others, from request bodies or from responses. */
    private static Set<String> reachable(OpenAPI api, Map<String, Schema> schemas, boolean fromRequests) {
        Deque<Schema<?>> pending = new ArrayDeque<>();
        for (PathItem item : api.getPaths().values()) {
            for (Operation operation : item.readOperations()) {
                if (fromRequests) {
                    if (operation.getRequestBody() != null) {
                        addContent(operation.getRequestBody().getContent(), pending);
                    }
                } else if (operation.getResponses() != null) {
                    for (ApiResponse response : operation.getResponses().values()) {
                        addContent(response.getContent(), pending);
                    }
                }
            }
        }
        Set<String> names = new HashSet<>();
        while (!pending.isEmpty()) {
            Schema<?> schema = pending.pop();
            if (schema.get$ref() != null) {
                String name = schema.get$ref().replace(SCHEMA_REF, "");
                if (names.add(name) && schemas.get(name) != null) {
                    pending.push(schemas.get(name));
                }
                continue;
            }
            if (schema.getProperties() != null) {
                schema.getProperties().values().forEach(pending::push);
            }
            if (schema.getItems() != null) {
                pending.push(schema.getItems());
            }
            if (schema.getAdditionalProperties() instanceof Schema<?> values) {
                pending.push(values);
            }
            for (List<Schema> group : new List[] {schema.getAllOf(), schema.getAnyOf(), schema.getOneOf()}) {
                if (group != null) {
                    group.forEach(pending::push);
                }
            }
        }
        return names;
    }

    private static void addContent(Content content, Deque<Schema<?>> pending) {
        if (content == null) {
            return;
        }
        for (MediaType media : content.values()) {
            if (media.getSchema() != null) {
                pending.push(media.getSchema());
            }
        }
    }
}
