package com.aicompany.backend.api;

import java.util.Map;

/**
 * A domain refusal that names its own {@link ApiProblem}.
 *
 * <p>PHASE 8 onwards. Every domain before it has one exception class and one
 * {@code @ExceptionHandler} per problem, and they stay as they are. The domains
 * of the ecosystem (software, catalogs, workspace, planning, orchestration,
 * daily work) extend this instead: the mapping is still explicit -- the problem
 * is a constructor argument, not inferred -- and {@code ApiProblemCoverageTest}
 * still refuses an exception in a domain package that is not mapped, because it
 * accepts a handler for a supertype.
 */
public abstract class ProblemException extends RuntimeException {

    private final transient ApiProblem problem;
    private final transient Map<String, String> errors;

    protected ProblemException(ApiProblem problem, String detail) {
        this(problem, detail, Map.of());
    }

    protected ProblemException(ApiProblem problem, String detail, Map<String, String> errors) {
        super(detail);
        this.problem = problem;
        this.errors = Map.copyOf(errors);
    }

    public ApiProblem problem() {
        return problem;
    }

    public Map<String, String> errors() {
        return errors;
    }
}
