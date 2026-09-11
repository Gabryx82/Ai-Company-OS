package com.aicompany.backend.task.controller;

import com.aicompany.backend.task.dto.TaskCreateRequest;
import com.aicompany.backend.task.dto.TaskProjectAssignmentRequest;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping
    public List<TaskResponse> getTasks() {
        return service.findAll();
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody TaskCreateRequest request) {

        TaskResponse created = service.create(
                request.title(),
                request.description(),
                request.status(),
                request.priority(),
                request.projectId());

        return ResponseEntity
                .created(URI.create("/api/tasks/" + created.id()))
                .body(created);
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
    public TaskResponse assignToProject(@PathVariable Long id,
                                        @Valid @RequestBody TaskProjectAssignmentRequest request) {

        return service.assignToProject(id, request.projectId());
    }
}
