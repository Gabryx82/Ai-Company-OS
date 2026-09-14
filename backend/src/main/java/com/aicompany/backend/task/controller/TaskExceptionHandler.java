package com.aicompany.backend.task.controller;

import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error contract for the task endpoints that touch the project relation.
 *
 * <p>Scoped, like its counterpart on the project side, and for a narrower reason:
 * the exceptions handled here are exactly the ones TASK-003 introduces. Nothing
 * an existing endpoint could already raise is listed, so the responses
 * {@code GET /api/tasks} and a rejected {@code POST /api/tasks} produce are
 * unchanged -- in particular Bean Validation failures are left to Spring's
 * default, which is what keeps the 400 shape of the task API exactly as it was.
 *
 * <p>The new endpoints therefore answer with {@code ProblemDetail} while the
 * older ones on the same prefix do not. That is the same disharmony TASK-002
 * accepted and recorded as TD-07, deliberately not widened here: making the
 * whole API speak one error dialect is its own decision, and it changes
 * responses that agents and tasks already return.
 */
@RestControllerAdvice(assignableTypes = {TaskController.class, ProjectTaskController.class})
class TaskExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    ProblemDetail handleTaskNotFound(TaskNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Task not found", e.getMessage());
    }

    /**
     * A project referenced by a task request that does not resolve.
     *
     * <p>404 even when it is the body of a {@code POST /api/tasks} that names it:
     * the same cause gets the same status wherever it appears, and the title says
     * which of the two identifiers in the request was the problem -- which is the
     * part a client can act on.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    ProblemDetail handleProjectNotFound(ProjectNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Project not found", e.getMessage());
    }

    @ExceptionHandler(ArchivedProjectCannotReceiveTasksException.class)
    ProblemDetail handleArchivedProject(ArchivedProjectCannotReceiveTasksException e) {
        return problem(HttpStatus.CONFLICT, "Archived project cannot receive tasks", e.getMessage());
    }

    /**
     * The other half of the archival rule, from ADR-006 §2: a task inside an
     * archived project does not move.
     *
     * <p>A separate title from the one above, on the same status code, because
     * the two refusals ask the caller to restore <em>different</em> projects --
     * the destination in one case, the one the task is sitting in here. Folding
     * them into one message would leave a client knowing it has to restore
     * something without knowing what.
     */
    @ExceptionHandler(ArchivedProjectTaskIsImmutableException.class)
    ProblemDetail handleFrozenTask(ArchivedProjectTaskIsImmutableException e) {
        return problem(HttpStatus.CONFLICT, "Task in an archived project cannot be modified", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
