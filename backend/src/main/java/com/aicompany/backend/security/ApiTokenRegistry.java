package com.aicompany.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a presented bearer token to the name it was configured under.
 *
 * <h2>Machines, not people</h2>
 *
 * <p>Since PHASE 15 (ADR-024) people sign in with a username and a password; a
 * configured token is a <em>service</em> credential for scripts and automation.
 * None is required: an empty registry means no machine may call, not that the
 * API is open -- every route still needs a principal. A blank value is "not
 * configured" and skipped, so {@code AICOS_OPERATOR_TOKEN} can simply be unset.
 * What stays refused at startup is a token too short to be a credential and two
 * names sharing one token -- the second would make the principal a coin toss.
 *
 * <h2>What is kept, and how it is compared</h2>
 *
 * <p>Only SHA-256 digests are held after construction, and the comparison is
 * {@link MessageDigest#isEqual}, which does not stop at the first differing
 * byte. Every configured digest is compared, match or not, so the time taken
 * does not depend on which entry -- if any -- matched. Hashing first also means
 * the compared values always have the same length, which is what makes a
 * constant-time comparison meaningful for inputs of arbitrary length.
 */
public final class ApiTokenRegistry {

    /** Below this a value is a password somebody typed, not a token. */
    static final int MINIMUM_TOKEN_LENGTH = 16;

    private final Map<String, byte[]> digestsByName;

    public ApiTokenRegistry(Map<String, String> tokensByName) {

        Map<String, byte[]> digests = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        (tokensByName == null ? Map.<String, String>of() : tokensByName).forEach((name, token) -> {
            if (token == null || token.isBlank()) {
                return;
            }
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("An API token is configured without a name");
            }
            if (token.strip().length() < MINIMUM_TOKEN_LENGTH) {
                throw new IllegalStateException("The API token '" + name + "' is shorter than "
                        + MINIMUM_TOKEN_LENGTH + " characters");
            }
            if (!seen.add(token)) {
                throw new IllegalStateException("The API token '" + name
                        + "' is also configured under another name; each principal needs its own");
            }
            digests.put(name, digest(token));
        });

        this.digestsByName = Map.copyOf(digests);
    }

    /** The principal the token was issued to, or empty if it is not one of ours. */
    public Optional<String> principalFor(String presentedToken) {

        if (presentedToken == null || presentedToken.isEmpty()) {
            return Optional.empty();
        }

        byte[] presented = digest(presentedToken);
        String match = null;

        for (Map.Entry<String, byte[]> entry : digestsByName.entrySet()) {
            // No early exit: the loop visits every entry whatever happens.
            if (MessageDigest.isEqual(entry.getValue(), presented)) {
                match = entry.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    public Set<String> principals() {
        return digestsByName.keySet();
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException(impossible);
        }
    }
}
