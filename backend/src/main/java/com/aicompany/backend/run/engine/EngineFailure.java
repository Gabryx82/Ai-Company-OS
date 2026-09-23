package com.aicompany.backend.run.engine;

/**
 * A completion that did not happen, with a stable type (ADR-016 §4).
 *
 * <p>When the engine answered with a problem detail, {@link #type()} is the
 * engine's own {@code urn:ai-company-os:engine:problem:*} -- relayed, not
 * translated, so a failed run says which provider problem it was. When the
 * engine could not be reached or answered something unreadable, the type is one
 * of {@link RunFailures}, which the control plane owns.
 */
public class EngineFailure extends RuntimeException {

    private final String type;

    public EngineFailure(String type, String detail) {
        super(detail);
        this.type = type;
    }

    public String type() {
        return type;
    }

    public String detail() {
        return getMessage();
    }
}
