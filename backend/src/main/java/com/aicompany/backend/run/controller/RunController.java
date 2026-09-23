package com.aicompany.backend.run.controller;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.run.dto.RunCreateRequest;
import com.aicompany.backend.run.dto.RunResponse;
import com.aicompany.backend.run.service.RunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.List;

/**
 * Runs: launched as a sub-resource of the task, read on their own (ADR-016 §6).
 *
 * <p>{@code 202 Accepted}, not {@code 201}: the run exists when the response is
 * sent, but its outcome does not -- the client polls {@code GET /api/runs/{id}}.
 * {@code If-Match} carries the <strong>task's</strong> tag, because launching may
 * start the task; the task's new tag is read with {@code GET /api/tasks/{id}}.
 */
@RestController
public class RunController {

    private final RunService service;

    public RunController(RunService service) {
        this.service = service;
    }

    @PostMapping("/api/tasks/{taskId}/runs")
    public ResponseEntity<RunResponse> launch(
            @PathVariable Long taskId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) RunCreateRequest request,
            Principal principal) {

        RunResponse run = service.launch(taskId, request == null ? null : request.model(),
                principal.getName(), Precondition.fromHeader(ifMatch));

        return ResponseEntity.accepted().location(URI.create("/api/runs/" + run.id())).body(run);
    }

    @GetMapping("/api/tasks/{taskId}/runs")
    public List<RunResponse> runsOfTask(@PathVariable Long taskId) {
        return service.findByTask(taskId);
    }

    @GetMapping("/api/runs/{runId}")
    public RunResponse run(@PathVariable Long runId) {
        return service.findById(runId);
    }
}
