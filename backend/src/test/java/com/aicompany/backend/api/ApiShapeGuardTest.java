package com.aicompany.backend.api;

import jakarta.persistence.Entity;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 -- the audit's debts about the shape of the API (TASK-000: TD-03,
 * TD-05, TD-06, TD-10), each a property of every controller and every class,
 * read by reflection: one added tomorrow is checked like the ones of today.
 */
class ApiShapeGuardTest {

    private static final String ROOT = "com.aicompany.backend";
    private static final List<Class<?>> classes = new ArrayList<>();
    private static final List<Method> handlers = new ArrayList<>();

    @BeforeAll
    static void scan() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true; // interfaces, records, abstract classes: everything
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        ClassLoader loader = ApiShapeGuardTest.class.getClassLoader();
        for (BeanDefinition definition : scanner.findCandidateComponents(ROOT)) {
            // Loaded, not initialised; and only the application's classes, not the tests'.
            Class<?> type = Class.forName(definition.getBeanClassName(), false, loader);
            if (!type.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes")) {
                classes.add(type);
            }
        }
        for (Class<?> type : classes) {
            if (AnnotatedElementUtils.hasAnnotation(type, RestController.class)) {
                for (Method method : type.getDeclaredMethods()) {
                    if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                        handlers.add(method);
                    }
                }
            }
        }
        assertThat(handlers).as("the scan must find the API, or these checks prove nothing").hasSizeGreaterThan(100);
    }

    // --- TD-05: JPA entities exposed as the API contract ---------------------------

    @Test
    void td05_noEntityIsPartOfWhatTheApiAnswers() {
        List<String> leaks = new ArrayList<>();
        for (Method handler : handlers) {
            Set<Class<?>> reached = new LinkedHashSet<>();
            reach(handler.getGenericReturnType(), reached);
            reached.stream().filter(type -> type.isAnnotationPresent(Entity.class))
                    .forEach(entity -> leaks.add(name(handler) + " -> " + entity.getSimpleName()));
        }
        assertThat(leaks).as("answer with a DTO, never with an entity").isEmpty();
    }

    // --- TD-06: request bodies bound through an implicit constructor ---------------

    @Test
    void td06_everyRequestBodyIsAnExplicitRecord() {
        List<String> implicit = new ArrayList<>();
        for (Method handler : handlers) {
            for (Parameter parameter : handler.getParameters()) {
                if (!parameter.isAnnotationPresent(RequestBody.class)) {
                    continue;
                }
                Class<?> type = parameter.getType();
                boolean explicit = type.isRecord() || type == String.class || type == byte[].class
                        || Map.class.isAssignableFrom(type) || List.class.isAssignableFrom(type);
                if (!explicit) {
                    implicit.add(name(handler) + "(" + type.getSimpleName() + ")");
                }
            }
        }
        assertThat(implicit).as("a request body is a record: its constructor is its contract").isEmpty();
    }

    // --- TD-03: no input validation ------------------------------------------------

    @Test
    void td03_aRequestBodyWithConstraintsIsAlwaysValidated() {
        List<String> unchecked = new ArrayList<>();
        for (Method handler : handlers) {
            for (Parameter parameter : handler.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class) && declaresConstraints(parameter.getType(), new HashSet<>())
                        && !parameter.isAnnotationPresent(Valid.class) && !parameter.isAnnotationPresent(Validated.class)) {
                    unchecked.add(name(handler) + "(" + parameter.getType().getSimpleName() + ")");
                }
            }
        }
        assertThat(unchecked).as("constraints that nothing enforces: add @Valid").isEmpty();
    }

    // --- TD-10: layering violations ---------------------------------------------------

    @Test
    void td10_eachLayerHoldsOnlyItsOwnKindOfClass() {
        List<String> misplaced = new ArrayList<>();
        for (Class<?> type : classes) {
            if (type.isMemberClass() || type.isAnonymousClass() || type.isSynthetic()) {
                continue;
            }
            String layer = type.getPackageName().substring(type.getPackageName().lastIndexOf('.') + 1);
            switch (layer) {
                case "repository" -> {
                    if (!(type.isInterface() && Repository.class.isAssignableFrom(type))) {
                        misplaced.add(type.getName() + ": a repository package holds Spring Data repositories");
                    }
                }
                case "controller" -> {
                    if (!AnnotatedElementUtils.hasAnnotation(type, RestController.class)
                            && !AnnotatedElementUtils.hasAnnotation(type, ControllerAdvice.class)) {
                        misplaced.add(type.getName() + ": a controller package holds controllers");
                    }
                }
                case "model" -> {
                    if (AnnotatedElementUtils.hasAnnotation(type, Component.class)
                            || CommandLineRunner.class.isAssignableFrom(type) || ApplicationRunner.class.isAssignableFrom(type)) {
                        misplaced.add(type.getName() + ": a model package holds no beans and no runners");
                    }
                }
                default -> {
                    // other packages are free in their shape
                }
            }
        }
        assertThat(misplaced).isEmpty();
    }

    // --- TD-59: names the contract would silently merge or renumber --------------------

    /**
     * springdoc names a schema after the class's simple name, so two DTOs with the same
     * simple name overwrite each other in the contract without a word; and it names an
     * operation after the handler method, numbering duplicates ({@code create_3}) in scan
     * order, so adding a controller could rename an existing operation.
     */
    @Test
    void td59_everyTypeAndOperationOfTheContractHasAUniqueName() {
        java.util.Map<String, Set<Class<?>>> types = new java.util.TreeMap<>();
        java.util.Map<String, List<String>> operations = new java.util.TreeMap<>();
        for (Method handler : handlers) {
            Set<Class<?>> reached = new LinkedHashSet<>();
            reach(handler.getGenericReturnType(), reached);
            for (Parameter parameter : handler.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    reach(parameter.getParameterizedType(), reached);
                }
            }
            reached.stream().filter(type -> !type.isEnum())
                    .forEach(type -> types.computeIfAbsent(type.getSimpleName(), k -> new LinkedHashSet<>()).add(type));
            operations.computeIfAbsent(handler.getName(), k -> new ArrayList<>()).add(name(handler));
        }
        assertThat(types.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " " + e.getValue()).toList())
                .as("schemas that would overwrite each other").isEmpty();
        assertThat(operations.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " " + e.getValue()).toList())
                .as("operations springdoc would number").isEmpty();
    }

    // --- helpers --------------------------------------------------------------------

    private static boolean declaresConstraints(Class<?> type, Set<Class<?>> seen) {
        if (!seen.add(type) || !type.getName().startsWith(ROOT)) {
            return false;
        }
        if (constrained(type)) {
            return true;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (constrained(component) || constrained(component.getAccessor())
                        || declaresConstraints(component.getType(), seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean constrained(AnnotatedElement element) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class) || annotation instanceof Valid) {
                return true;
            }
        }
        if (element instanceof RecordComponent component) {
            // a constraint on a component's type argument (List<@NotBlank String>)
            return component.getAnnotatedType().toString().contains("@jakarta.validation");
        }
        return false;
    }

    /** Every application type a value of this type can carry: generic arguments, record components, fields. */
    private static void reach(Type type, Set<Class<?>> reached) {
        if (type instanceof ParameterizedType parameterized) {
            reach(parameterized.getRawType(), reached);
            for (Type argument : parameterized.getActualTypeArguments()) {
                reach(argument, reached);
            }
        } else if (type instanceof GenericArrayType array) {
            reach(array.getGenericComponentType(), reached);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                reach(bound, reached);
            }
        } else if (type instanceof Class<?> raw) {
            if (raw.isArray()) {
                reach(raw.getComponentType(), reached);
                return;
            }
            if (!raw.getName().startsWith(ROOT) || !reached.add(raw)) {
                return;
            }
            if (raw.isRecord()) {
                for (RecordComponent component : raw.getRecordComponents()) {
                    reach(component.getGenericType(), reached);
                }
            } else {
                for (var field : raw.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        reach(field.getGenericType(), reached);
                    }
                }
            }
        }
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
