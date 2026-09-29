package com.aicompany.backend.blueprint.model;

/**
 * How the path of a block relates to a real file (ADR-033 §2). {@code LINKED} and
 * {@code MISSING} are facts the server establishes; {@code TO_CREATE},
 * {@code DOCUMENT_REFERENCE} and {@code VIRTUAL} are what the operator says a path
 * that does not exist is.
 */
public enum PathState {
    LINKED, TO_CREATE, DOCUMENT_REFERENCE, VIRTUAL, MISSING;

    /** The states an operator may choose for a manual block whose path is not a file. */
    public boolean isChosenByTheOperator() {
        return this == TO_CREATE || this == DOCUMENT_REFERENCE || this == VIRTUAL;
    }
}
