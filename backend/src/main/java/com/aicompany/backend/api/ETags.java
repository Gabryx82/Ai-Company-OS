package com.aicompany.backend.api;

/**
 * Renders a row version as an entity-tag.
 *
 * <p><strong>Strong, not weak</strong>, and not as a matter of taste: RFC 9110
 * requires {@code If-Match} to use the strong comparison function, so a weak tag
 * would never match and would be decorative.
 *
 * <p>The price is declared. A strong entity-tag asserts octet equality of the
 * representation, while this token tracks the state of a row: if the shape of a
 * response ever changed without the row changing, two different representations
 * would share a tag. That is TD-32. It does not affect {@code If-Match}, which
 * compares state versions, only HTTP caching, which this project does not use.
 */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }
}
