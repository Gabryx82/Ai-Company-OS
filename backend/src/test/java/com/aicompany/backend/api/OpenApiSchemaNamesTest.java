package com.aicompany.backend.api;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 36 (closes TD-97): springdoc names a schema by the simple name of its type, and
 * two different types with the same simple name become one schema -- the first one met --
 * without an error: a route then describes the wrong body, and the console's generated
 * types with it (PHASE 35: the measures' {@code Report} described as the Hub's). Every
 * type of the application that a route takes or returns, directly or inside another,
 * has a simple name of its own.
 */
class OpenApiSchemaNamesTest extends AbstractPostgresTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyTypeOfTheContractHasASimpleNameOfItsOwn() {
        Deque<Type> pending = new ArrayDeque<>();
        for (HandlerMethod method : handlerMapping.getHandlerMethods().values()) {
            pending.add(method.getMethod().getGenericReturnType());
            for (MethodParameter parameter : method.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    pending.add(parameter.getGenericParameterType());
                }
            }
        }
        Map<String, Set<String>> byName = new TreeMap<>();
        Set<Type> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Type type = pending.poll();
            if (!seen.add(type)) {
                continue;
            }
            switch (type) {
                case ParameterizedType p -> {
                    pending.add(p.getRawType());
                    pending.addAll(List.of(p.getActualTypeArguments()));
                }
                case GenericArrayType a -> pending.add(a.getGenericComponentType());
                case WildcardType w -> pending.addAll(List.of(w.getUpperBounds()));
                case Class<?> c when c.isArray() -> pending.add(c.getComponentType());
                case Class<?> c when c.getName().startsWith("com.aicompany.") && !c.isEnum() -> {
                    byName.computeIfAbsent(c.getSimpleName(), k -> new TreeSet<>()).add(c.getName());
                    if (c.isRecord()) {
                        for (RecordComponent component : c.getRecordComponents()) {
                            pending.add(component.getGenericType());
                        }
                    }
                }
                default -> {
                    // a JDK or library type: not a schema of ours
                }
            }
        }
        Map<String, Set<String>> shared = new TreeMap<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                shared.put(name, classes);
            }
        });
        assertThat(byName).as("the walk reached the contract's types").containsKeys("Readiness", "ReadinessItem", "Orchestration");
        assertThat(shared).as("simple names that would be one schema for different types").isEmpty();
    }
}
