package com.aicompany.backend.api;

/**
 * No {@code If-Match} on a route that mutates a resource which already exists.
 *
 * <p>Rule P0. Raised while interpreting the header, before the database is
 * touched: the precondition is a property of the request, not of the resource, so
 * a caller who stated none is told about the precondition rather than about the
 * existence of a row.
 */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("If-Match is required for this request");
    }
}
