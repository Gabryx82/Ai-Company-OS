package com.aicompany.backend.api;

/**
 * The resource moved on since the caller read it.
 *
 * <p>Not a domain refusal: nothing about the current state forbids this request.
 * It is a refusal about the caller's <em>knowledge</em> of the state -- which is
 * why it is a 412 and not one of the 409s that ADR-004, ADR-005 and ADR-006 use.
 * "The state does not allow this" and "you did not know what the state was" ask
 * for different things from a client, and reusing one code for both would make
 * them indistinguishable.
 */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException() {
        super("The resource changed since the ETag you supplied; re-read it and try again");
    }
}
