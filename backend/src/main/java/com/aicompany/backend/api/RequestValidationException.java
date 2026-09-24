package com.aicompany.backend.api;

import java.util.Map;

/**
 * A request that Bean Validation accepted and the domain did not, reported as
 * the same {@code validation-failed} problem with the same {@code errors} map --
 * a client cannot tell, and should not need to, which layer refused a field.
 */
public class RequestValidationException extends ProblemException {

    public RequestValidationException(String field, String message) {
        super(ApiProblem.VALIDATION_FAILED, "The request is not valid", Map.of(field, message));
    }
}
