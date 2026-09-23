package com.aicompany.backend.run.exception;

/**
 * The AI Engine could not answer a question the control plane asked on behalf of
 * a caller -- today only "which models exist" (TASK-021). A 503: the control plane
 * is fine, the service behind it is not, and the same request may work later.
 *
 * <p>Runs never raise this: a run that cannot reach the engine is a {@code FAILED}
 * run with a type (ADR-016 §4), not an HTTP error.
 */
public class EngineUnavailableException extends RuntimeException {

    public EngineUnavailableException(String detail) {
        super(detail);
    }
}
