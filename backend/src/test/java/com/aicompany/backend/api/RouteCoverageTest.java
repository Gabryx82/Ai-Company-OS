package com.aicompany.backend.api;

import com.aicompany.backend.support.RouteRecorder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 -- audit TD-09 ("functional test coverage: none"): every route the
 * controllers declare is reached by at least one test of the suite.
 *
 * <p>Reached means: a request of the suite was mapped to it and handled
 * ({@link RouteRecorder}, registered for every test context). This class runs
 * after all the others ({@code junit-platform.properties} orders classes by
 * {@link Order}); when only part of the suite runs ({@code -Dtest=…}) there is
 * nothing to compare and it is skipped. A new endpoint without a test turns
 * the full suite red.
 */
@Order(Integer.MAX_VALUE)
class RouteCoverageTest {

    @Test
    void td09_everyRouteIsReachedByATest() throws Exception {
        Assumptions.assumeTrue(System.getProperty("test") == null && System.getProperty("it.test") == null,
                "only part of the suite ran");
        Set<String> reached = RouteRecorder.reached();
        Assumptions.assumeTrue(reached.size() > 100, "the API tests did not run in this JVM");

        List<String> unreached = new ArrayList<>();
        for (String route : declared()) {
            String method = route.substring(0, route.indexOf(' '));
            String pattern = route.substring(route.indexOf(' ') + 1);
            boolean hit = method.equals("ANY")
                    ? reached.stream().anyMatch(r -> r.endsWith(" " + pattern))
                    : reached.contains(route);
            if (!hit) {
                unreached.add(route);
            }
        }
        assertThat(unreached).as("routes no test reaches").isEmpty();
    }

    /** Every route the controllers declare, as {@code "METHOD /pattern"} ({@code ANY} when the method is not restricted). */
    static Set<String> declared() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.aicompany.backend")) {
            Class<?> controller = Class.forName(definition.getBeanClassName(), false, RouteCoverageTest.class.getClassLoader());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method handler : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                RequestMethod[] methods = mapping.method();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        if (methods.length == 0) {
                            routes.add("ANY " + prefix + path);
                        }
                        for (RequestMethod method : methods) {
                            routes.add(method.name() + " " + prefix + path);
                        }
                    }
                }
            }
        }
        assertThat(routes).as("the scan must find the API").hasSizeGreaterThan(100);
        return routes;
    }
}
