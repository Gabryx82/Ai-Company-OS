package com.aicompany.backend.api;

import com.aicompany.backend.agent.exception.AgentNameConflictException;
import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.exception.IllegalAgentStateTransitionException;
import com.aicompany.backend.agent.exception.InactiveAgentIsImmutableException;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
import com.aicompany.backend.project.exception.ProjectNameConflictException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
import com.aicompany.backend.task.exception.InactiveAgentCannotReceiveTasksException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The error contract of the whole API, in one place.
 *
 * <p>Supersedes the two module-scoped advices of ADR-004 §6 and ADR-005 §8. Both
 * declared their own narrowness as temporary and recorded it as TD-07; this is
 * that decision being taken rather than deferred again.
 *
 * <p>It is global and it is the only one. Two advices that could both handle the
 * same exception make the response depend on a precedence nobody chose: a uniform
 * contract with two points of definition is not uniform, it is lucky.
 *
 * <p><strong>Why it extends {@link ResponseEntityExceptionHandler}.</strong> Half
 * of what a client sees is raised by Spring before any of our code runs --
 * unreadable body, missing content type, wrong method, an identifier that will
 * not parse, a path that matches nothing. Those are the families TD-20 and TD-27
 * named. The base class already knows all of them and keeps knowing the ones the
 * framework adds later; writing them out by hand would mean chasing that list at
 * every upgrade.
 *
 * <p>Every response is built through {@link ApiProblem}, so no handler can invent
 * an identifier and two problems cannot quietly share one.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // --- the precondition protocol (ADR-009) ------------------------------

    @ExceptionHandler(PreconditionRequiredException.class)
    ResponseEntity<ProblemDetail> handlePreconditionRequired(PreconditionRequiredException e) {
        return respond(ApiProblem.PRECONDITION_REQUIRED, e.getMessage());
    }

    /**
     * The one refusal in this class that is not about the state of a resource but
     * about what the caller knew of it. There is deliberately <strong>no</strong>
     * handler for {@code OptimisticLockException} next to it: on every write path
     * the entity is loaded under a pessimistic lock and therefore already carries
     * the newest version, so that exception cannot arrive. A handler for it would
     * be a piece of contract that never runs -- untestable except by absence.
     */
    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ProblemDetail> handlePreconditionFailed(PreconditionFailedException e) {
        return respond(ApiProblem.PRECONDITION_FAILED, e.getMessage());
    }

    @ExceptionHandler(InvalidPreconditionException.class)
    ResponseEntity<ProblemDetail> handleInvalidPrecondition(InvalidPreconditionException e) {
        return respond(ApiProblem.INVALID_PRECONDITION, e.getMessage());
    }

    // --- the project registry ---------------------------------------------

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ProblemDetail> handleProjectNotFound(ProjectNotFoundException e) {
        return respond(ApiProblem.PROJECT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ProjectNameConflictException.class)
    ResponseEntity<ProblemDetail> handleDuplicateName(ProjectNameConflictException e) {
        return respond(ApiProblem.PROJECT_NAME_CONFLICT, e.getMessage());
    }

    /**
     * Editing an archived project. A conflict rather than a 403: the refusal is
     * about the state of the resource, not about who is asking, and restoring it
     * makes the same request succeed (ADR-004 §8).
     */
    @ExceptionHandler(ArchivedProjectIsImmutableException.class)
    ResponseEntity<ProblemDetail> handleArchivedIsImmutable(ArchivedProjectIsImmutableException e) {
        return respond(ApiProblem.ARCHIVED_PROJECT_IS_IMMUTABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalProjectStateTransitionException.class)
    ResponseEntity<ProblemDetail> handleIllegalTransition(IllegalProjectStateTransitionException e) {
        return respond(ApiProblem.ILLEGAL_PROJECT_STATE_TRANSITION, e.getMessage());
    }

    // --- the agent registry -----------------------------------------------

    @ExceptionHandler(AgentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleAgentNotFound(AgentNotFoundException e) {
        return respond(ApiProblem.AGENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AgentNameConflictException.class)
    ResponseEntity<ProblemDetail> handleAgentNameConflict(AgentNameConflictException e) {
        return respond(ApiProblem.AGENT_NAME_CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InactiveAgentIsImmutableException.class)
    ResponseEntity<ProblemDetail> handleInactiveAgent(InactiveAgentIsImmutableException e) {
        return respond(ApiProblem.INACTIVE_AGENT_IS_IMMUTABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalAgentStateTransitionException.class)
    ResponseEntity<ProblemDetail> handleIllegalAgentTransition(IllegalAgentStateTransitionException e) {
        return respond(ApiProblem.ILLEGAL_AGENT_STATE_TRANSITION, e.getMessage());
    }

    // --- tasks and their project ------------------------------------------

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTaskNotFound(TaskNotFoundException e) {
        return respond(ApiProblem.TASK_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ArchivedProjectCannotReceiveTasksException.class)
    ResponseEntity<ProblemDetail> handleArchivedDestination(ArchivedProjectCannotReceiveTasksException e) {
        return respond(ApiProblem.ARCHIVED_PROJECT_CANNOT_RECEIVE_TASKS, e.getMessage());
    }

    /**
     * Distinct from the one above, on the same status, because the two refusals
     * ask the caller to restore <em>different</em> projects -- the destination
     * there, the one holding the task here.
     */
    @ExceptionHandler(ArchivedProjectTaskIsImmutableException.class)
    ResponseEntity<ProblemDetail> handleFrozenTask(ArchivedProjectTaskIsImmutableException e) {
        return respond(ApiProblem.ARCHIVED_PROJECT_TASK_IS_IMMUTABLE, e.getMessage());
    }

    @ExceptionHandler(InactiveAgentCannotReceiveTasksException.class)
    ResponseEntity<ProblemDetail> handleInactiveDestination(InactiveAgentCannotReceiveTasksException e) {
        return respond(ApiProblem.INACTIVE_AGENT_CANNOT_RECEIVE_TASKS, e.getMessage());
    }

    // --- what the caller sent, as Spring sees it --------------------------

    /**
     * Bean Validation. The status is what it always was; what is new outside
     * {@code /api/projects} is the list of offending fields, so a client does not
     * have to guess which one was rejected.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ApiProblem.VALIDATION_FAILED.toDetail();
        problem.setProperty("errors", errors);
        return body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(ApiProblem.MALFORMED_REQUEST.toDetail());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(ApiProblem.UNSUPPORTED_MEDIA_TYPE.toDetail());
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(ApiProblem.METHOD_NOT_ALLOWED.toDetail(
                e.getMethod() + " is not available on this path"));
    }

    /**
     * An identifier or a query parameter that will not convert -- {@code abc} as
     * a path variable, an unknown value for {@code ?status=}.
     *
     * <p>This is TD-27: the same route used to answer in two different dialects
     * depending on whether the identifier was wrong or unparseable.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String name = e instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : "a parameter";
        return body(ApiProblem.INVALID_PARAMETER.toDetail(
                "'" + e.getValue() + "' is not a valid value for '" + name + "'"));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(ApiProblem.RESOURCE_NOT_FOUND.toDetail());
    }

    // --- everything nobody anticipated ------------------------------------

    /**
     * The catch-all. Without one, the only response outside the contract would be
     * the one a client understands least: the unexpected failure.
     *
     * <p>Locking failures land here, and deliberately so. ADR-006 §7 refused a
     * <em>policy</em> -- no {@code lock_timeout}, no {@code Retry-After}, no 503
     * and no retry contract -- and recorded the missing <em>shape</em> as TD-29.
     * The shape is what this gives them; nothing about timeouts or retries is
     * promised here.
     *
     * <p>It does not need to exclude anything the base class handles: Spring picks
     * the most specific handler, and everything {@link ResponseEntityExceptionHandler}
     * declares is more specific than {@code Exception}.
     *
     * <p>A catch-all can mask a defect by making it look handled, which is why
     * this logs at ERROR with the full stack: the log line is where the defect
     * stays visible.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleAnythingElse(Exception e) {

        log.error("Unhandled exception, reported as {}", ApiProblem.INTERNAL_ERROR.slug(), e);
        return respond(ApiProblem.INTERNAL_ERROR, null);
    }

    // --- rendering ---------------------------------------------------------

    private static ResponseEntity<ProblemDetail> respond(ApiProblem problem, String detail) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem.toDetail(detail));
    }

    private static ResponseEntity<Object> body(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
