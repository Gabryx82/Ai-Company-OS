package com.aicompany.backend.security;

import com.aicompany.backend.user.service.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/**
 * The browser's session (PHASE 32, TD-53; ADR-024 §8 revised): the session token
 * travels in a cookie the page cannot read.
 *
 * <ul>
 *   <li><strong>HttpOnly</strong>: no script sees it, so an injected one cannot
 *       carry it away -- the reason for the change;</li>
 *   <li><strong>SameSite=Strict, Path=/api</strong>: never attached to a request
 *       another site starts, nor outside the API;</li>
 *   <li><strong>Secure</strong> when the request came over HTTPS;</li>
 *   <li>no Max-Age: it ends with the browser, and the server's idle and absolute
 *       limits (ADR-024 §2) end it earlier.</li>
 * </ul>
 *
 * <p>The name carries the port: a browser keeps cookies by host, not by port, and
 * two control planes on one host (the operator's and a test one) would otherwise
 * sign each other out.
 *
 * <p>A cookie is attached by the browser by itself, which is what CSRF abuses.
 * So every write that authenticates with it must also carry {@link #CSRF_HEADER}
 * with {@link SessionService#csrfToken(String)}: a value only the page that signed
 * in received, and a custom header no other origin can send without a CORS
 * preflight the control plane refuses.
 */
public final class SessionCookie {

    public static final String PREFIX = "aicos_session_";

    public static final String CSRF_HEADER = "X-AICOS-CSRF";

    /** Set by the filter on a request authenticated by the cookie: the anti-forgery token of that session. */
    public static final String CSRF_ATTRIBUTE = SessionCookie.class.getName() + ".csrf";

    private SessionCookie() {
    }

    public static String name(HttpServletRequest request) {
        return PREFIX + request.getServerPort();
    }

    public static ResponseCookie issue(HttpServletRequest request, String token) {
        return ResponseCookie.from(name(request), token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api")
                .build();
    }

    /** The same cookie, expired: what signing out sends back. */
    public static ResponseCookie clear(HttpServletRequest request) {
        return ResponseCookie.from(name(request), "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api")
                .maxAge(0)
                .build();
    }

    static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = name(request);
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank())
                .map(Cookie::getValue)
                .findFirst();
    }

    /** The header against the session's token, in constant time. */
    static boolean confirmed(HttpServletRequest request, String token) {
        String sent = request.getHeader(CSRF_HEADER);
        return sent != null && MessageDigest.isEqual(sent.strip().getBytes(StandardCharsets.UTF_8),
                SessionService.csrfToken(token).getBytes(StandardCharsets.UTF_8));
    }
}
