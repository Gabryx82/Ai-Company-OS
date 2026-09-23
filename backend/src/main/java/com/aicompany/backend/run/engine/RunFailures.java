package com.aicompany.backend.run.engine;

/**
 * The failure types the control plane itself assigns to a run (ADR-016 §4).
 *
 * <p>Not HTTP problems -- no client request fails with these; they are the
 * recorded reason a run ended {@code FAILED}. A separate URN space from both the
 * API's problems and the engine's, so a reader of a run always knows who decided
 * it failed.
 */
public final class RunFailures {

    private static final String PREFIX = "urn:ai-company-os:run-failure:";

    /** Nothing answered at the engine's address. */
    public static final String ENGINE_UNREACHABLE = PREFIX + "engine-unreachable";

    /** The engine did not answer within the read timeout. */
    public static final String ENGINE_TIMEOUT = PREFIX + "engine-timeout";

    /** The engine answered with something this contract cannot read. */
    public static final String ENGINE_PROTOCOL = PREFIX + "engine-protocol";

    /** The run was queued or running when the control plane stopped (startup recovery). */
    public static final String INTERRUPTED = PREFIX + "interrupted";

    /** No executor thread would take it: the queue was full. */
    public static final String REJECTED = PREFIX + "rejected";

    /** Anything else, logged in full; the run says only that it was internal. */
    public static final String INTERNAL = PREFIX + "internal";

    private RunFailures() {
    }
}
