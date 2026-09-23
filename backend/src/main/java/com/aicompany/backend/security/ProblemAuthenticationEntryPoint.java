package com.aicompany.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The 401, rendered by the same advice that renders every other error.
 *
 * <p>The refusal happens in the filter chain, before any controller, so the
 * {@code @RestControllerAdvice} would not see it on its own. Rather than write a
 * second renderer here -- a second place where the contract is defined, which is
 * exactly what ADR-007 removed -- the exception is handed to the MVC exception
 * resolvers, and {@code ApiExceptionHandler} answers it like anything else.
 */
final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver resolver;

    ProblemAuthenticationEntryPoint(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) {
        resolver.resolveException(request, response, null, exception);
    }
}
