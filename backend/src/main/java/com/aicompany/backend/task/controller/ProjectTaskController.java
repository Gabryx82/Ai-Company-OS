package com.aicompany.backend.task.controller;

import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The tasks of a project, read as a sub-resource of the project.
 *
 * <p>It lives in the task package, not the project one, on purpose. The
 * dependency between the two modules runs one way -- a task knows its project,
 * a project knows nothing about tasks -- and putting this mapping on
 * {@code ProjectController} would have made it run both ways for the sake of a
 * URL prefix.
 *
 * <p>An unknown project is a 404 rather than an empty list: the service resolves
 * the project before querying.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class ProjectTaskController {

    private final TaskService service;

    public ProjectTaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskResponse> tasksOfProject(@PathVariable Long projectId) {
        return service.findAllByProject(projectId);
    }
}
