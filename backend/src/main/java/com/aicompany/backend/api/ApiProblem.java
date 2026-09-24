package com.aicompany.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * The closed set of problems this API can report.
 *
 * <p>Each one owns three things that must not drift apart: a stable identifier,
 * the status it is reported with, and a human-readable title. The identifier is
 * the contract; the title is prose (ADR-007 §2).
 *
 * <p>That separation is the point of this enum. Before it existed, the only way
 * to tell two conflicts apart from the outside was to compare their English
 * sentence -- which made the sentence part of the contract without anyone
 * deciding so, and froze it against correction. A client branches on
 * {@code type}; a title can be rewritten tomorrow.
 *
 * <p>The identifier is a URN and not an {@code https://} URL. RFC 9457 allows any
 * URI and does not require it to resolve, and pointing at a domain we do not own
 * would assert documentation that does not exist and that somebody else could one
 * day serve. If public documentation ever exists, moving to {@code https://} is
 * its own decision with its own breaking note.
 *
 * <p>Enumerating them, rather than letting each handler compose its own, is what
 * makes two problems sharing a slug impossible and adding one a deliberate act in
 * a single place. A test asserts the exact set.
 */
public enum ApiProblem {

    // --- what the caller sent ---------------------------------------------

    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST,
            "Invalid request payload", "The request body failed validation"),

    MALFORMED_REQUEST("malformed-request", HttpStatus.BAD_REQUEST,
            "Malformed request", "The request body could not be read"),

    INVALID_PARAMETER("invalid-parameter", HttpStatus.BAD_REQUEST,
            "Invalid request parameter", "A value in the request could not be interpreted"),

    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported media type", "This endpoint does not accept that content type"),

    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED,
            "Method not allowed", "That method is not available on this path"),

    RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND,
            "Resource not found", "There is nothing at this path"),

    // --- who is asking (ADR-013) -------------------------------------------

    /**
     * No credential, or one that is not ours -- the same answer for both, on
     * purpose (ADR-013 §4). Rendered by this contract like everything else, even
     * though it is raised in the filter chain and not by a controller.
     */
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED,
            "Authentication required", "This request must carry a valid bearer token"),

    // --- the precondition protocol (ADR-009) ------------------------------

    /**
     * Rule P0. A 428 and not a 400 because it tells the caller what to do about
     * it: read the resource, take its ETag, send the request again.
     */
    PRECONDITION_REQUIRED("precondition-required", HttpStatus.PRECONDITION_REQUIRED,
            "Precondition required",
            "This request must carry an If-Match header with the ETag you last read"),

    /**
     * The detection itself. Distinct from every 409 in this enum on purpose: those
     * say the current state forbids the request, this says the caller did not know
     * what the current state was. A client retries one of them and re-reads before
     * the other.
     */
    PRECONDITION_FAILED("precondition-failed", HttpStatus.PRECONDITION_FAILED,
            "Precondition failed",
            "The resource changed since the ETag you supplied; re-read it and try again"),

    /**
     * Unreadable, weak, or {@code *}. One identifier for all three because they
     * are one problem -- the If-Match value cannot be evaluated -- and the detail
     * says which. ADR-007 §2 forbids telling two <em>different</em> problems apart
     * by their English sentence; it does not ask for one problem to be split.
     */
    INVALID_PRECONDITION("invalid-precondition", HttpStatus.BAD_REQUEST,
            "Invalid precondition", "The If-Match header could not be interpreted"),

    // --- the project registry ---------------------------------------------

    PROJECT_NOT_FOUND("project-not-found", HttpStatus.NOT_FOUND,
            "Project not found", "No project with that identifier"),

    PROJECT_NAME_CONFLICT("project-name-conflict", HttpStatus.CONFLICT,
            "Project name already in use", "Another project already has that name"),

    ARCHIVED_PROJECT_IS_IMMUTABLE("archived-project-is-immutable", HttpStatus.CONFLICT,
            "Archived project is immutable", "Restore the project before editing it"),

    ILLEGAL_PROJECT_STATE_TRANSITION("illegal-project-state-transition", HttpStatus.CONFLICT,
            "Illegal project state transition", "The project is not in a state that allows this"),

    // --- the agent registry -----------------------------------------------

    AGENT_NOT_FOUND("agent-not-found", HttpStatus.NOT_FOUND,
            "Agent not found", "No agent with that identifier"),

    AGENT_NAME_CONFLICT("agent-name-conflict", HttpStatus.CONFLICT,
            "Agent name already in use", "Another agent already has that name"),

    INACTIVE_AGENT_IS_IMMUTABLE("inactive-agent-is-immutable", HttpStatus.CONFLICT,
            "Inactive agent is immutable", "Activate the agent before editing it"),

    ILLEGAL_AGENT_STATE_TRANSITION("illegal-agent-state-transition", HttpStatus.CONFLICT,
            "Illegal agent state transition", "The agent is not in a state that allows this"),

    // --- tasks and their project ------------------------------------------

    TASK_NOT_FOUND("task-not-found", HttpStatus.NOT_FOUND,
            "Task not found", "No task with that identifier"),

    ARCHIVED_PROJECT_CANNOT_RECEIVE_TASKS("archived-project-cannot-receive-tasks", HttpStatus.CONFLICT,
            "Archived project cannot receive tasks", "Restore the destination project first"),

    ARCHIVED_PROJECT_TASK_IS_IMMUTABLE("archived-project-task-is-immutable", HttpStatus.CONFLICT,
            "Task in an archived project cannot be modified", "Restore the project that holds it first"),

    /**
     * Distinct from {@link #ARCHIVED_PROJECT_CANNOT_RECEIVE_TASKS} on the same
     * status, because the two refusals ask the caller for different things: there,
     * restore a project; here, activate an agent. They also rest on different
     * arguments -- containment against responsibility, ADR-010 D1 -- which is why
     * one identifier could not have served both.
     */
    INACTIVE_AGENT_CANNOT_RECEIVE_TASKS("inactive-agent-cannot-receive-tasks", HttpStatus.CONFLICT,
            "Inactive agent cannot be given work", "Activate the agent first"),

    // --- the task lifecycle (ADR-014) ----------------------------------------

    ILLEGAL_TASK_STATE_TRANSITION("illegal-task-state-transition", HttpStatus.CONFLICT,
            "Illegal task state transition", "The task is not in a state that allows this"),

    /**
     * Distinct from {@link #INACTIVE_AGENT_CANNOT_RECEIVE_TASKS}, which a start on a
     * task whose agent is inactive also returns: the remedy here is to assign
     * somebody, there to activate or reassign.
     */
    UNASSIGNED_TASK_CANNOT_START("unassigned-task-cannot-start", HttpStatus.CONFLICT,
            "Task has no agent", "Assign the task to an agent before starting it"),

    // --- runs (ADR-016) -----------------------------------------------------

    RUN_NOT_FOUND("run-not-found", HttpStatus.NOT_FOUND,
            "Run not found", "No run with that identifier"),

    TASK_RUN_IN_PROGRESS("task-run-in-progress", HttpStatus.CONFLICT,
            "Task already has a run in progress", "Wait for the current run to finish"),

    FINISHED_TASK_CANNOT_RUN("finished-task-cannot-run", HttpStatus.CONFLICT,
            "Finished task cannot run", "Reopen the task before running it again"),

    /** TASK-021. The control plane is fine; the AI Engine behind it did not answer. */
    ENGINE_UNAVAILABLE("engine-unavailable", HttpStatus.SERVICE_UNAVAILABLE,
            "AI Engine unavailable", "The AI Engine did not answer; is it running?"),

    // --- ecosystem catalogs (PHASE 8, ADR-018/019) ---------------------------

    SOFTWARE_NOT_FOUND("software-not-found", HttpStatus.NOT_FOUND,
            "Software not found", "No software with that key in the catalog"),

    SOFTWARE_KEY_CONFLICT("software-key-conflict", HttpStatus.CONFLICT,
            "Software key already exists", "Another catalog entry already uses that key"),

    /** ADR-019 I5: web entries, disabled entries and known incompatibilities are not launched. */
    SOFTWARE_NOT_LAUNCHABLE("software-not-launchable", HttpStatus.CONFLICT,
            "Software cannot be launched", "This catalog entry cannot be launched from the control plane"),

    /** ADR-019 I6: not Windows, or the test profile. */
    LAUNCHER_UNAVAILABLE("launcher-unavailable", HttpStatus.SERVICE_UNAVAILABLE,
            "Launcher unavailable", "Launching local software is not supported where the control plane runs"),

    LAUNCH_FAILED("launch-failed", HttpStatus.BAD_GATEWAY,
            "Launch failed", "The operating system did not start the program"),

    PROVIDER_NOT_FOUND("provider-not-found", HttpStatus.NOT_FOUND,
            "Provider not found", "No model provider with that key"),

    MODEL_NOT_FOUND("model-not-found", HttpStatus.NOT_FOUND,
            "Model not found", "No catalogued model with that key"),

    QUOTA_PLAN_NOT_FOUND("quota-plan-not-found", HttpStatus.NOT_FOUND,
            "Quota plan not found", "No quota window with that key"),

    // --- project workspace (PHASE 9, ADR-020) ---------------------------------

    WORKSPACE_NOT_CONFIGURED("workspace-not-configured", HttpStatus.CONFLICT,
            "Project has no workspace", "Set the project's workspace folder first"),

    /** ADR-020 W2/W3: outside the workspace, or not a document this API writes. */
    WORKSPACE_PATH_REFUSED("workspace-path-refused", HttpStatus.BAD_REQUEST,
            "Path refused", "The path is outside the project workspace or not a project document"),

    WORKSPACE_FILE_NOT_FOUND("workspace-file-not-found", HttpStatus.NOT_FOUND,
            "File not found", "No such file in the project workspace"),

    WORKSPACE_UNAVAILABLE("workspace-unavailable", HttpStatus.SERVICE_UNAVAILABLE,
            "Workspace unavailable", "The project folder could not be read or written"),

    // --- planning (PHASE 10, ADR-021) --------------------------------------------

    PHASE_NOT_FOUND("phase-not-found", HttpStatus.NOT_FOUND,
            "Phase not found", "No phase with that identifier"),

    /** ADR-022: the Human-in-the-Loop gate. A task of a phase nobody approved does not start. */
    PHASE_NOT_APPROVED("phase-not-approved", HttpStatus.CONFLICT,
            "Phase not approved", "Approve the task's phase before starting or running it"),

    MASTER_PROMPT_MISSING("master-prompt-missing", HttpStatus.CONFLICT,
            "Master prompt missing", "Write MASTER_PROMPT.md before generating the plan"),

    PLAN_INVALID("plan-invalid", HttpStatus.UNPROCESSABLE_ENTITY,
            "Plan invalid", "The plan does not follow the plan.json format"),

    PLAN_LOCKED("plan-locked", HttpStatus.CONFLICT,
            "Plan locked", "The current plan cannot be replaced"),

    PLANNING_IN_PROGRESS("planning-in-progress", HttpStatus.CONFLICT,
            "Planning in progress", "A plan is already being generated for this project"),

    // --- everything nobody anticipated ------------------------------------

    /**
     * The catch-all. Its {@code detail} is fixed on purpose: an internal
     * exception's message carries table names, SQL fragments and paths, and
     * returning it opens an information channel nobody decided to open. The
     * exception itself is logged in full, which is where a defect stays visible
     * (ADR-007 §4).
     */
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal error", "The request could not be completed");

    private static final String URN_PREFIX = "urn:ai-company-os:problem:";

    private final String slug;
    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;

    ApiProblem(String slug, HttpStatus status, String title, String defaultDetail) {
        this.slug = slug;
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public String slug() {
        return slug;
    }

    public URI type() {
        return URI.create(URN_PREFIX + slug);
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The problem with its own default wording. */
    public ProblemDetail toDetail() {
        return toDetail(defaultDetail);
    }

    /** The problem, with a detail that says something specific about this occurrence. */
    public ProblemDetail toDetail(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, detail == null || detail.isBlank() ? defaultDetail : detail);
        problem.setType(type());
        problem.setTitle(title);
        return problem;
    }
}
