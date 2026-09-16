package com.aicompany.backend.support;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;

/**
 * Builds the precondition a caller would send, for tests that exercise the
 * services directly.
 *
 * <p>It goes the long way round on purpose -- version to entity-tag to
 * {@link Precondition#fromHeader} -- rather than constructing the object. There
 * is no other door into {@code Precondition}, and there must not be one: a
 * shortcut for tests would let them exercise a path no client can reach, and the
 * parsing rules of ADR-009 §5.3 would stop being covered by the tests that use
 * this the most.
 */
public final class Preconditions {

    private Preconditions() {
    }

    /** The precondition of a caller that last saw the resource at this version. */
    public static Precondition at(long version) {
        return Precondition.fromHeader(ETags.of(version));
    }
}
