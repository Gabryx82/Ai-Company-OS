package com.aicompany.backend.project.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.dto.ProjectCreateRequest;
import com.aicompany.backend.project.dto.ProjectResponse;
import com.aicompany.backend.project.dto.ProjectUpdateRequest;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.service.ProjectService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * HTTP surface of the project registry.
 *
 * <p>There is no {@code DELETE} mapping, and that is the point: a project leaves
 * the working registry through {@code POST /{id}/archive}, never by being
 * removed. Because the path exists for other methods, a {@code DELETE} request
 * gets 405 rather than 404, which says "not this way" instead of "not here".
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectResponse> list(@RequestParam(required = false) ProjectStatus status) {
        return service.findAll(status)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable Long id) {
        return ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {

        Project created = service.create(request.name(), request.description());

        return ResponseEntity
                .created(URI.create("/api/projects/" + created.getId()))
                .eTag(ETags.of(created.getVersion()))
                .body(ProjectResponse.from(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ProjectUpdateRequest request) {

        return ok(service.update(id, request.name(), request.description(),
                Precondition.fromHeader(ifMatch)));
    }

    /**
     * A transition carries the precondition as much as an edit does, and for a
     * sharper reason: an archive and a restore issued from the same read are two
     * intentions formed against one state, and only one of them can still be
     * acting on it. Rule P0 admits no exception, which is the same clause L7
     * needed for the same reason.
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archive(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        return ok(service.archive(id, Precondition.fromHeader(ifMatch)));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ProjectResponse> restore(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        return ok(service.restore(id, Precondition.fromHeader(ifMatch)));
    }

    /**
     * Every single-resource response carries the entity-tag, so the one place a
     * client can learn a version is the one place it is always told.
     *
     * <p>{@code required = false} above is deliberate: with {@code true} Spring
     * would raise its own exception and the advice would report a generic 400,
     * where rule P0 asks for the 428 that tells the caller what to do instead.
     */
    private static ResponseEntity<ProjectResponse> ok(Project project) {
        return ResponseEntity.ok()
                .eTag(ETags.of(project.getVersion()))
                .body(ProjectResponse.from(project));
    }
}
