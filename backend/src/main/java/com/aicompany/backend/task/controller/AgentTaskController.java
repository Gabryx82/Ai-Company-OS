package com.aicompany.backend.task.controller;

import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The tasks of an agent, read as a sub-resource of the agent.
 *
 * <p>It lives in the task package and not the agent one, for the reason
 * {@link ProjectTaskController} gives: the dependency between the modules runs
 * one way -- a task knows its agent, an agent knows nothing about tasks -- and
 * putting this mapping on {@code AgentController} would have made it run both
 * ways for the sake of a URL prefix. Keeping it one-way is also what stops
 * {@code Agent} growing a collection of tasks, which is what would turn ADR-010
 * D3 from a domain decision into a cascade flag.
 *
 * <p>An unknown agent is a 404 rather than an empty list: the service resolves
 * the agent before querying. An <em>inactive</em> one answers normally -- taking
 * an agent out of the working registry does not make the work it holds
 * unreadable, and a client that has just deactivated an agent needs this listing
 * precisely in order to reassign what it finds.
 *
 * <p>No entity-tag on the response: a collection has no version of its own
 * (ADR-009 §5.2). A client that intends to write reads the task.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tasks")
public class AgentTaskController {

    private final TaskService service;

    public AgentTaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskResponse> tasksOfAgent(@PathVariable Long agentId) {
        return service.findAllByAgent(agentId);
    }
}
