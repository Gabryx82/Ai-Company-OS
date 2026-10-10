package com.aicompany.backend.security;

import com.aicompany.backend.user.exception.AuthProblemException;
import com.aicompany.backend.user.service.SecurityLog;
import com.aicompany.backend.user.service.SessionService;
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
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a credential into an authenticated principal: {@code Authorization:
 * Bearer <token>} (services, scripts, tests) or the browser's session cookie
 * (the console, since PHASE 32 -- TD-53, {@link SessionCookie}).
 *
 * <p>For the header, three outcomes, and the difference between the second and
 * the third is the decision:
 *
 * <ol>
 *   <li>a token of ours -- a live session or a configured service token
 *       (ADR-024): the request continues as that {@link Caller}, with its role;</li>
 *   <li><strong>no Authorization header</strong>: the cookie is looked at, then
 *       the request continues anonymously and the authorization rules decide.
 *       That keeps the public routes public without this filter having to know
 *       which they are;</li>
 *   <li><strong>a header that is not a valid credential</strong> -- wrong token,
 *       wrong scheme, no scheme: refused here, now, with the same 401 an
 *       anonymous caller gets. Letting it continue anonymously would make a
 *       wrong token work on public routes, which is harmless today and a
 *       debugging trap the day a client sends a stale token and wonders why
 *       some routes answer.</li>
 * </ol>
 *
 * <p>The cookie is different because the browser attaches it by itself:
 *
 * <ul>
 *   <li>a cookie that is not a live session is <strong>ignored</strong>, not
 *       refused -- a session that ended leaves its cookie behind, and the sign-in
 *       must still answer. Protected routes then answer the anonymous 401;</li>
 *   <li>it is not read on the sign-in routes, which are public and must not
 *       depend on what the browser happens to hold;</li>
 *   <li>a write (anything but GET, HEAD, OPTIONS) authenticated by it needs the
 *       session's {@link SessionCookie#CSRF_HEADER}, or it is a
 *       {@code 403 csrf-rejected} recorded in the security log.</li>
 * </ul>
 *
 * <p>Not a Spring bean on purpose: a {@code Filter} bean is also registered with
 * the servlet container by Boot, and this one must run exactly once, inside the
 * security chain.
 */
final class CredentialAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "bearer ";

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** Where a person gets a credential: the cookie the browser holds plays no part in them. */
    private static final Set<String> SIGN_IN = Set.of("/api/auth/login", "/api/auth/session");

    private final CallerResolver resolver;
    private final AuthenticationEntryPoint entryPoint;
    private final HandlerExceptionResolver problems;
    private final SecurityLog securityLog;

    CredentialAuthenticationFilter(CallerResolver resolver, AuthenticationEntryPoint entryPoint,
                                   HandlerExceptionResolver problems, SecurityLog securityLog) {
        this.resolver = resolver;
        this.entryPoint = entryPoint;
        this.problems = problems;
        this.securityLog = securityLog;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null) {
            Optional<Caller> principal = token(header).flatMap(resolver::resolve);
            if (principal.isEmpty()) {
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response,
                        new BadCredentialsException("The bearer token is not valid"));
                return;
            }
            continueAs(principal.get(), request, response, chain);
            return;
        }

        Optional<String> cookie = isSignIn(request) ? Optional.empty() : SessionCookie.read(request);
        Optional<Caller> session = cookie.flatMap(resolver::resolveSession);
        if (session.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (!READS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                && !SessionCookie.confirmed(request, cookie.get())) {
            securityLog.record(SecurityLog.ACCESS_DENIED, session.get().name(), request.getRemoteAddr(),
                    "CSRF: " + request.getMethod() + " " + request.getRequestURI() + " without the session's "
                            + SessionCookie.CSRF_HEADER);
            problems.resolveException(request, response, null, AuthProblemException.csrfRejected());
            return;
        }
        request.setAttribute(SessionCookie.CSRF_ATTRIBUTE, SessionService.csrfToken(cookie.get()));
        continueAs(session.get(), request, response, chain);
    }

    private static void continueAs(Caller caller, HttpServletRequest request, HttpServletResponse response,
                                   FilterChain chain) throws ServletException, IOException {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                caller, null, AuthorityUtils.createAuthorityList(caller.role().authority())));
        SecurityContextHolder.setContext(securityContext);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean isSignIn(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && SIGN_IN.contains(request.getRequestURI());
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
