package com.aicompany.backend.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Test-only (PHASE 32, audit TD-09): every route a test reaches, as
 * {@code "METHOD /pattern"}. Registered for every application context the suite
 * starts, through {@code META-INF/spring/…AutoConfiguration.imports} in the test
 * resources, so no test has to opt in; {@code RouteCoverageTest} runs last and
 * compares what was reached with what the controllers declare.
 */
@AutoConfiguration
public class RouteRecorder {

    private static final Set<String> REACHED = new ConcurrentSkipListSet<>();

    public static Set<String> reached() {
        return REACHED;
    }

    @Bean
    WebMvcConfigurer routeRecordingConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                                Exception failure) {
                        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                        if (pattern != null) {
                            REACHED.add(request.getMethod() + " " + pattern);
                        }
                    }
                });
            }
        };
    }
}
