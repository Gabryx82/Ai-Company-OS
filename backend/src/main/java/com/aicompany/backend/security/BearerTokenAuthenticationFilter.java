package com.aicompany.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns {@code Authorization: Bearer <token>} into an authenticated principal.
 *
 * <p>Three outcomes, and the difference between the second and the third is the
 * decision:
 *
 * <ol>
 *   <li>a token of ours: the request continues as its principal;</li>
 *   <li><strong>no Authorization header</strong>: the request continues
 *       anonymously, and the authorization rules decide. That keeps the public
 *       routes public without this filter having to know which they are;</li>
 *   <li><strong>a header that is not a valid credential</strong> -- wrong token,
 *       wrong scheme, no scheme: refused here, now, with the same 401 an
 *       anonymous caller gets. Letting it continue anonymously would make a
 *       wrong token work on public routes, which is harmless today and a
 *       debugging trap the day a client sends a stale token and wonders why
 *       some routes answer.</li>
 * </ol>
 *
 * <p>Not a Spring bean on purpose: a {@code Filter} bean is also registered with
 * the servlet container by Boot, and this one must run exactly once, inside the
 * security chain.
 */
final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "bearer ";

    /** Every authenticated caller is the operator today: one role, no RBAC (ADR-013 §3). */
    static final String OPERATOR_AUTHORITY = "ROLE_OPERATOR";

    private final ApiTokenRegistry registry;
    private final AuthenticationEntryPoint entryPoint;

    BearerTokenAuthenticationFilter(ApiTokenRegistry registry, AuthenticationEntryPoint entryPoint) {
        this.registry = registry;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<String> principal = token(header).flatMap(registry::principalFor);

        if (principal.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response,
                    new BadCredentialsException("The bearer token is not valid"));
            return;
        }

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal.get(), null, AuthorityUtils.createAuthorityList(OPERATOR_AUTHORITY)));
        SecurityContextHolder.setContext(securityContext);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * The token, if the header is a bearer credential. The scheme is compared
     * case-insensitively, as RFC 9110 §11.1 requires; the token is compared
     * exactly, because a credential that "almost" matches is not one.
     */
    private static Optional<String> token(String header) {
        String value = header.strip();
        if (value.length() <= SCHEME.length()
                || !value.substring(0, SCHEME.length()).toLowerCase(Locale.ROOT).equals(SCHEME)) {
            return Optional.empty();
        }
        return Optional.of(value.substring(SCHEME.length()).strip());
    }
}
