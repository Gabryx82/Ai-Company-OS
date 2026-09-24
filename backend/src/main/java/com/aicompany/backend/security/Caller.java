package com.aicompany.backend.security;

import com.aicompany.backend.user.model.Role;

import java.security.Principal;

/**
 * Who is calling (ADR-024 §1): a signed-in person, or a machine holding a
 * configured token. {@link #getName()} is what the rest of the system records
 * as "who did it", as it did before people could sign in.
 */
public record Caller(String name, Role role, Kind kind, Long userId, Long sessionId) implements Principal {

    public enum Kind { USER, SERVICE }

    public static Caller service(String name, Role role) {
        return new Caller(name, role, Kind.SERVICE, null, null);
    }

    public static Caller user(String username, Role role, Long userId, Long sessionId) {
        return new Caller(username, role, Kind.USER, userId, sessionId);
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
