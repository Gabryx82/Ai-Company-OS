package com.aicompany.backend.task.controller;

import com.aicompany.backend.agent.routing.AgentRouter;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Which active agents fit this task (TD-08). Read-only: the operator accepts a
 * suggestion by assigning the task, through the route that has the rules
 * ({@code PUT /api/tasks/{id}/agent}).
 *
 * <p>In the task package, like the other task sub-resources: the dependency runs
 * from tasks to agents, never back.
 */
@RestController
public class TaskRoutingController {

    private final TaskService tasks;
    private final AgentRouter router;

    public TaskRoutingController(TaskService tasks, AgentRouter router) {
        this.tasks = tasks;
        this.router = router;
    }

    @GetMapping("/api/tasks/{id}/agent-suggestions")
    public List<AgentRouter.Suggestion> suggestionsFor(@PathVariable Long id) {
        TaskResponse task = tasks.findById(id).body();
        return router.suggest(task.title() + " " + (task.description() == null ? "" : task.description()));
    }
}
