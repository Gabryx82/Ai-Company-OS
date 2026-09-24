package com.aicompany.backend.security;

import com.aicompany.backend.user.model.Role;
import com.aicompany.backend.user.service.SessionService;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A bearer token to a {@link Caller} (ADR-024 §1): a signed-in session first --
 * session tokens carry a prefix that no configured token needs -- then a
 * configured service token.
 */
public class CallerResolver {

    private final ApiTokenRegistry registry;
    private final Map<String, String> roles;
    private final SessionService sessions;

    public CallerResolver(ApiTokenRegistry registry, Map<String, String> roles, SessionService sessions) {
        this.registry = registry;
        this.roles = roles == null ? Map.of() : Map.copyOf(roles);
        this.sessions = sessions;
        this.roles.forEach((name, role) -> Role.valueOf(role.strip().toUpperCase(Locale.ROOT)));
    }

    public Optional<Caller> resolve(String token) {
        if (token.startsWith(SessionService.TOKEN_PREFIX)) {
            return sessions.resolve(token);
        }
        return registry.principalFor(token).map(name -> Caller.service(name, roleOf(name)));
    }

    private Role roleOf(String name) {
        String role = roles.get(name);
        return role == null ? Role.OPERATOR : Role.valueOf(role.strip().toUpperCase(Locale.ROOT));
    }
}
