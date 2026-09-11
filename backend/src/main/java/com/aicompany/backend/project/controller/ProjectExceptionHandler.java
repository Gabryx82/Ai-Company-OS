package com.aicompany.backend.project.controller;

import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
import com.aicompany.backend.project.exception.ProjectNameConflictException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error contract for the project registry.
 *
 * <p>Scoped to {@link ProjectController} on purpose. A global advice would
 * silently change the responses the agent and task endpoints already return, and
 * a uniform error contract for the whole API is its own decision — recorded as
 * open debt TD-07 — not something to smuggle in with a new module.
 */
@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectExceptionHandler {

    @ExceptionHandler(ProjectNotFoundException.class)
    ProblemDetail handleNotFound(ProjectNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Project not found", e.getMessage());
    }

    @ExceptionHandler(ProjectNameConflictException.class)
    ProblemDetail handleDuplicateName(ProjectNameConflictException e) {
        return problem(HttpStatus.CONFLICT, "Project name already in use", e.getMessage());
    }

    /**
     * Editing an archived project. A conflict rather than a 403: the request is
     * refused because of the state of the resource, and restoring it makes the
     * same request succeed.
     */
    @ExceptionHandler(ArchivedProjectIsImmutableException.class)
    ProblemDetail handleArchivedIsImmutable(ArchivedProjectIsImmutableException e) {
        return problem(HttpStatus.CONFLICT, "Archived project is immutable", e.getMessage());
    }

    @ExceptionHandler(IllegalProjectStateTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalProjectStateTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Illegal project state transition", e.getMessage());
    }

    /**
     * Bean Validation failures. Spring already answers 400 without this handler;
     * what it adds is the list of offending fields, so a client does not have to
     * guess which one was rejected.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "Invalid project payload", "The request body failed validation");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * An unknown value for {@code ?status=}. Without this it would surface as a
     * 500, because the failure happens while binding the parameter to the enum.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request parameter",
                "'" + e.getValue() + "' is not a valid value for '" + e.getName() + "'");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
