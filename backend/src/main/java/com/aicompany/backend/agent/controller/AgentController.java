package com.aicompany.backend.agent.controller;

import com.aicompany.backend.agent.dto.AgentCreateRequest;
import com.aicompany.backend.agent.dto.AgentResponse;
import com.aicompany.backend.agent.dto.AgentUpdateRequest;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.service.AgentService;
import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
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
    public ResponseEntity<AgentResponse> getAgent(@PathVariable Long id) {
        return ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<AgentResponse> createAgent(@Valid @RequestBody AgentCreateRequest request) {

        Agent created = service.create(request.name(), request.role(), request.specialization(), request.model());

        return ResponseEntity.created(URI.create("/api/agents/" + created.getId()))
                .eTag(ETags.of(created.getVersion()))
                .body(AgentResponse.from(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentResponse> updateAgent(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AgentUpdateRequest request) {

        return ok(service.update(id, request.name(), request.role(), request.specialization(), request.model(),
                Precondition.fromHeader(ifMatch)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<AgentResponse> deactivateAgent(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        return ok(service.deactivate(id, Precondition.fromHeader(ifMatch)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<AgentResponse> activateAgent(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        return ok(service.activate(id, Precondition.fromHeader(ifMatch)));
    }

    /**
     * The agent registry adopts the precondition protocol for the reason ADR-009
     * §4 gives: it has no registered debt for stale overwrites only because
     * TASK-007 arrived after TD-28 was written down, not because its write surface
     * is any different. A precondition with holes is a promise that holds wherever
     * somebody remembered it, and a client cannot tell where that is.
     *
     * <p>{@code required = false} is deliberate -- see {@link Precondition#fromHeader}.
     */
    private static ResponseEntity<AgentResponse> ok(Agent agent) {
        return ResponseEntity.ok()
                .eTag(ETags.of(agent.getVersion()))
                .body(AgentResponse.from(agent));
    }
}
