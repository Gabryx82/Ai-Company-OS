package com.aicompany.backend.security;

import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Which browser origins may call the API, and what their scripts may see (TD-11).
 *
 * <p>Declared, never inferred: {@code aicos.cors.allowed-origins}. Empty means no
 * browser client at all, which is the right default for {@code prod} -- a console
 * that nobody configured is a console that does not exist.
 *
 * <ul>
 *   <li><strong>No wildcard.</strong> {@code *} is refused at startup rather than
 *       honoured. With bearer tokens it would not leak a credential by itself, but
 *       it would make "which page is allowed to drive the control plane" a question
 *       nobody answered.</li>
 *   <li><strong>No credentialed requests.</strong> The browser attaches nothing by
 *       itself; the page sends the token explicitly. That is what keeps CSRF out of
 *       the picture (ADR-013 §3).</li>
 *   <li><strong>{@code If-Match} in, {@code ETag} out.</strong> Without the
 *       exposed header a browser hides the entity-tag from script, and every
 *       mutation of ADR-009 becomes impossible from a page.</li>
 * </ul>
 */
public final class CorsPolicy {

    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

    static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.IF_MATCH, HttpHeaders.ACCEPT);

    static final List<String> EXPOSED_HEADERS = List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION);

    private final List<String> allowedOrigins;

    private CorsPolicy(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public static CorsPolicy from(List<String> configuredOrigins) {

        List<String> origins = configuredOrigins == null ? List.of() : configuredOrigins.stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();

        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalStateException("aicos.cors.allowed-origins contains '" + origin
                        + "': wildcards are refused, declare each origin the console is served from");
            }
            if (!isOrigin(origin)) {
                throw new IllegalStateException("aicos.cors.allowed-origins contains '" + origin
                        + "', which is not an origin (scheme://host[:port], no path)");
            }
        }
        return new CorsPolicy(List.copyOf(origins));
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    CorsConfigurationSource toSource() {

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins.isEmpty()) {
            return source;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofHours(1));

        source.registerCorsConfiguration("/api/**", configuration);

        // The liveness probe too, read-only. Found by the first live run of the
        // console (TASK-022): the page asks /actuator/health to show whether the
        // control plane is up, and without CORS the browser hid the answer -- the
        // console said "down" for a backend that was up.
        CorsConfiguration health = new CorsConfiguration();
        health.setAllowedOrigins(allowedOrigins);
        health.setAllowedMethods(List.of("GET"));
        health.setAllowCredentials(false);
        source.registerCorsConfiguration("/actuator/health", health);
        return source;
    }

    private static boolean isOrigin(String candidate) {
        try {
            URI uri = URI.create(candidate);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
