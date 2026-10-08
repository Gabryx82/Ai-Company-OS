package com.aicompany.backend.api;

/**
 * Renders a row version as an entity-tag.
 *
 * <p><strong>Strong, not weak</strong>, and not as a matter of taste: RFC 9110
 * requires {@code If-Match} to use the strong comparison function, so a weak tag
 * would never match and would be decorative.
 *
 * <p>A strong entity-tag asserts octet equality of the representation, while a
 * row version tracks the state of a row. So the tag a controller sets,
 * {@code "<version>"}, is completed before the body is written into
 * {@code "<version>.<digest>"} ({@link RepresentationTagAdvice}, PHASE 32, closing
 * TD-32): two different representations never share a tag, and the version in
 * front is what {@link Precondition} compares.
 */
public final class ETags {

    private ETags() {
    }

    /** The state tag: what a controller sets, and what a client may build from a listed version. */
    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** The full tag: the state, and the digest of the representation's bytes. */
    public static String of(long version, String representationDigest) {
        return "\"" + version + "." + representationDigest + "\"";
    }
}
