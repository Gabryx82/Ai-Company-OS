package com.aicompany.backend.user.service;

import com.aicompany.backend.user.exception.AuthProblemException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * The password rules (ADR-024 §2), kept to what matters: length, the BCrypt
 * limit, and the obvious guesses. No composition rules -- length is what makes a
 * password strong, and composition rules produce {@code Password1!}.
 */
public final class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 12;
    /** BCrypt reads at most 72 bytes; anything past them would be silently ignored. */
    public static final int MAXIMUM_BYTES = 72;

    private static final Set<String> OBVIOUS = Set.of("password", "passwordpassword", "123456789012",
            "qwertyuiopas", "adminadminadmin", "changemechangeme", "aicompanyos123");

    private PasswordPolicy() {
    }

    public static void check(String password, String username) {
        if (password == null || password.codePointCount(0, password.length()) < MINIMUM_LENGTH) {
            throw AuthProblemException.weakPassword("The password must be at least " + MINIMUM_LENGTH + " characters long");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw AuthProblemException.weakPassword("The password must be at most " + MAXIMUM_BYTES + " bytes long");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (OBVIOUS.contains(lower) || lower.chars().distinct().count() < 4) {
            throw AuthProblemException.weakPassword("The password is too easy to guess");
        }
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            throw AuthProblemException.weakPassword("The password must not contain the username");
        }
    }
}
