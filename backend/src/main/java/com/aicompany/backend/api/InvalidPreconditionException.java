package com.aicompany.backend.api;

/**
 * The {@code If-Match} value is not something this API can evaluate: unreadable,
 * weak, or the wildcard.
 *
 * <p>A 400 and not a 412, because 412 tells a caller to re-read and retry, and
 * re-reading would not help any of these three. The caller's code is wrong, not
 * its knowledge of the state.
 */
public class InvalidPreconditionException extends RuntimeException {

    public InvalidPreconditionException(String message) {
        super(message);
    }
}
