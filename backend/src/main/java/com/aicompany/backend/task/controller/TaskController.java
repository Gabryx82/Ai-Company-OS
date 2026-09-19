package com.aicompany.backend.task.controller;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.Versioned;
import com.aicompany.backend.task.dto.TaskAgentAssignmentRequest;
import com.aicompany.backend.task.dto.TaskCreateRequest;
import com.aicompany.backend.task.dto.TaskProjectAssignmentRequest;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /**
     * The listing, which carries no entity-tag: a collection has no version of its
     * own, and giving it one would assert an aggregate that no row represents. A
     * client that intends to write reads the single resource first -- ADR-009 §5.2,
     * and the cost of that round trip is TD-33.
     */
    @GetMapping
    public List<TaskResponse> getTasks() {
        return service.findAll();
    }

    /**
     * One task, and the canonical way to learn its entity-tag.
     *
     * <p>Introduced by TASK-008 because the precondition needs a source: without
     * this route the only way to read a task's version would be to page through
     * every task there is. It also turns {@code DELETE /api/tasks/{id}} into a 405
     * rather than a 404 -- "not this way" instead of "nothing here" -- which is
     * what the project registry already does on purpose.
     *
     * <p>No lock: rule L6.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long id) {
        return ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody TaskCreateRequest request) {

        Versioned<TaskResponse> created = service.create(
                request.title(),
                request.description(),
                request.statusValue(),
                request.priority(),
                request.projectId(),
                request.agentId());

        return ResponseEntity
                .created(URI.create("/api/tasks/" + created.body().id()))
                .eTag(created.etag())
                .body(created.body());
    }

    /**
     * Puts a task in a project, or moves it to a different one.
     *
     * <p>A sub-resource rather than a field of a general task update, because
     * there is no general task update: {@code PUT} on the association replaces
     * the association and says so in the URL, and the shape stays right when the
     * rest of a task becomes editable.
     */
    @PutMapping("/{id}/project")
    public ResponseEntity<TaskResponse> assignToProject(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TaskProjectAssignmentRequest request) {

        return ok(service.assignToProject(id, request.projectId(), Precondition.fromHeader(ifMatch)));
    }

    /**
     * Puts a task in the hands of an agent, or moves it to a different one.
     *
     * <p>A sub-resource for the same reason the project assignment is one, and
     * deliberately the mirror of it: the two associations of a task are two
     * resources, and a client that has learned one route has learned both.
     *
     * <p>{@code If-Match} is required here from the day the route exists, rather
     * than added afterwards: rule P4 of ADR-009 makes the protocol hereditary, and
     * {@code PreconditionCoverageTest} is what makes that a rule rather than an
     * intention. The tag is the <strong>task's</strong> -- {@code agent_id} is a
     * column of that row, so there is one version and one tag for both
     * associations (ADR-010 §4).
     */
    @PutMapping("/{id}/agent")
    public ResponseEntity<TaskResponse> assignToAgent(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TaskAgentAssignmentRequest request) {

        return ok(service.assignToAgent(id, request.agentId(), Precondition.fromHeader(ifMatch)));
    }

    /**
     * <h2>Why the header is optional to Spring and mandatory to us</h2>
     *
     * <p>With {@code required = true} Spring raises its own exception before any of
     * our code runs, and the advice reports a generic 400. Rule P0 asks for a 428,
     * which tells the caller what to do about it, so the decision has to be taken
     * by {@link Precondition#fromHeader} rather than by the binder.
     *
     * <p>The controller interprets the header and stops there. The comparison
     * belongs to whoever holds the row lock, and that is the service (rule P1).
     *
     * <p>The version travels in the {@code ETag} header and never in the body,
     * which is why the service hands back a {@link Versioned} rather than widening
     * the response record (ADR-009 §7).
     */
    private static ResponseEntity<TaskResponse> ok(Versioned<TaskResponse> task) {
        return ResponseEntity.ok().eTag(task.etag()).body(task.body());
    }
}
