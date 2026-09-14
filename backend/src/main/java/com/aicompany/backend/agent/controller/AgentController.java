package com.aicompany.backend.agent.controller;

import com.aicompany.backend.agent.dto.AgentCreateRequest;
import com.aicompany.backend.agent.dto.AgentResponse;
import com.aicompany.backend.agent.dto.AgentUpdateRequest;
import com.aicompany.backend.agent.service.AgentService;
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
 * The agent registry.
 *
 * <p>There is no {@code DELETE} mapping, for the reason ADR-004 §3 gave for
 * projects: an agent is destined to be the root of assignments, runs and memory,
 * and deleting a root asks every future subdomain "what happens to the children".
 * Agents are deactivated. Spring answers 405 on its own, which says "not by this
 * route" rather than "does not exist", and that is the correct message.
 *
 * <p>Errors go through the single global contract of ADR-007. There is no advice
 * here, and adding one would reintroduce the precedence problem TASK-005 removed.
 *
 * <p><strong>The bare {@code @CrossOrigin} that used to sit here is gone, and
 * that is deliberate.</strong> It allowed every origin, which was already TD-11;
 * what changed is that this controller no longer only reads. Carrying the
 * annotation forward would have opened creation, editing and both lifecycle
 * transitions to any origin -- a materially worse position than the one TD-11
 * recorded, arrived at as a side effect of an unrelated task. Removing it is not
 * a CORS decision and does not close TD-11: whatever policy this API ends up
 * with is its own task. It is the choice not to widen an open door while walking
 * past it.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    /**
     * Every agent, oldest first. {@code ?active=} filters; without it the listing
     * includes deactivated agents, which is the same choice the project registry
     * made and is still an open contract question there.
     */
    @GetMapping
    public List<AgentResponse> getAgents(@RequestParam(required = false) Boolean active) {
        return service.findAll(active).stream().map(AgentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AgentResponse getAgent(@PathVariable Long id) {
        return AgentResponse.from(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@Valid @RequestBody AgentCreateRequest request) {

        AgentResponse created = AgentResponse.from(
                service.create(request.name(), request.role(), request.specialization()));

        return ResponseEntity.created(URI.create("/api/agents/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public AgentResponse updateAgent(@PathVariable Long id,
                                     @Valid @RequestBody AgentUpdateRequest request) {

        return AgentResponse.from(
                service.update(id, request.name(), request.role(), request.specialization()));
    }

    @PostMapping("/{id}/deactivate")
    public AgentResponse deactivateAgent(@PathVariable Long id) {
        return AgentResponse.from(service.deactivate(id));
    }

    @PostMapping("/{id}/activate")
    public AgentResponse activateAgent(@PathVariable Long id) {
        return AgentResponse.from(service.activate(id));
    }
}
