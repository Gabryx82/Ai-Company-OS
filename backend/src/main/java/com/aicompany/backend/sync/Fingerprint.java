package com.aicompany.backend.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PHASE 37 (ADR-043): what a connector wrote, in 64 hexadecimal characters. Each
 * connector fingerprints what <em>it</em> writes -- the files, the request -- so that a
 * change a tool is never given (the system prompt, for a LiteLLM alias that only names
 * a model) does not make its copy look behind.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    /** @param parts what was written, in a fixed order; the first is the connector's own format version */
    static String of(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
