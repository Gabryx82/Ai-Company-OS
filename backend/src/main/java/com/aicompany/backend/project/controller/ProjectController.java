package com.aicompany.backend.project.controller;

import com.aicompany.backend.project.dto.ProjectCreateRequest;
import com.aicompany.backend.project.dto.ProjectResponse;
import com.aicompany.backend.project.dto.ProjectUpdateRequest;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public ProjectResponse getById(@PathVariable Long id) {
        return ProjectResponse.from(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {

        Project created = service.create(request.name(), request.description());

        return ResponseEntity
                .created(URI.create("/api/projects/" + created.getId()))
                .body(ProjectResponse.from(created));
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id,
                                  @Valid @RequestBody ProjectUpdateRequest request) {

        return ProjectResponse.from(service.update(id, request.name(), request.description()));
    }

    @PostMapping("/{id}/archive")
    public ProjectResponse archive(@PathVariable Long id) {
        return ProjectResponse.from(service.archive(id));
    }

    @PostMapping("/{id}/restore")
    public ProjectResponse restore(@PathVariable Long id) {
        return ProjectResponse.from(service.restore(id));
    }
}
