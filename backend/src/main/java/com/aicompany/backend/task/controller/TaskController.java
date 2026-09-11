package com.aicompany.backend.task.controller;

import com.aicompany.backend.task.dto.TaskCreateRequest;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return service.getAllTasks()
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody TaskCreateRequest request) {

        Task saved = service.save(new Task(
                request.title(),
                request.description(),
                request.status(),
                request.priority()
        ));

        return ResponseEntity
                .created(URI.create("/api/tasks/" + saved.getId()))
                .body(TaskResponse.from(saved));
    }

}
