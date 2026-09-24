package com.aicompany.backend.plan.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.plan.model.PlanRun;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.service.PlanningService;
import com.aicompany.backend.project.dto.ProjectResponse;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.dto.TaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The planning half of the Master Orchestrator (ADR-021) and its approvals (ADR-022). */
@RestController
public class PlanController {

    public record GenerateRequest(@Size(max = 200) String model) {
    }

    public record ReviewRequest(@Size(max = 2000) String note) {
    }

    public record PlanRunResponse(Long id, Long projectId, PlanRun.Source source, PlanRun.Status status,
                                  String requestedModel, String servedModel, String failureDetail, Integer phases,
                                  Integer tasks, Integer inputTokens, Integer outputTokens, String requestedBy,
                                  Instant createdAt, Instant finishedAt, String output) {
        public static PlanRunResponse from(PlanRun run) {
            return new PlanRunResponse(run.getId(), run.getProjectId(), run.getSource(), run.getStatus(),
                    run.getRequestedModel(), run.getServedModel(), run.getFailureDetail(), run.getPhases(),
                    run.getTasks(), run.getInputTokens(), run.getOutputTokens(), run.getRequestedBy(),
                    run.getCreatedAt(), run.getFinishedAt(), run.getOutput());
        }
    }

    public record PhaseResponse(Long id, Long projectId, int number, String title, String objective,
                                ProjectPhase.Status status, ProjectPhase.Approval approval, String approvalNote,
                                Instant approvedAt, String documentPath, long version, List<TaskResponse> tasks) {
        public static PhaseResponse from(ProjectPhase phase, List<TaskResponse> tasks) {
            return new PhaseResponse(phase.getId(), phase.getProjectId(), phase.getNumber(), phase.getTitle(),
                    phase.getObjective(), phase.getStatus(), phase.getApproval(), phase.getApprovalNote(),
                    phase.getApprovedAt(), phase.getDocumentPath(), phase.getVersion(), tasks);
        }
    }

    public record PlanResponse(ProjectResponse project, List<PhaseResponse> phases, List<PlanRunResponse> runs) {
    }

    private final PlanningService service;

    public PlanController(PlanningService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{id}/plan")
    public PlanResponse plan(@PathVariable Long id) {
        PlanningService.PlanView view = service.view(id);
        return new PlanResponse(ProjectResponse.from(view.project()),
                view.phases().stream().map(p -> PhaseResponse.from(p.phase(),
                        p.tasks().stream().map(TaskResponse::from).toList())).toList(),
                view.runs().stream().map(PlanRunResponse::from).toList());
    }

    /** Generates the plan from MASTER_PROMPT.md through the AI Engine. 202: it runs in the background. */
    @PostMapping("/api/projects/{id}/plan/generate")
    public ResponseEntity<PlanRunResponse> generate(@PathVariable Long id,
                                                    @Valid @RequestBody(required = false) GenerateRequest request,
                                                    Authentication caller) {
        PlanRun run = service.generate(id, request == null ? null : request.model(), caller.getName());
        return ResponseEntity.accepted().body(PlanRunResponse.from(run));
    }

    /** Imports .aicos/plan.json, written by an external agent that followed the planning handoff. */
    @PostMapping("/api/projects/{id}/plan/import")
    public PlanRunResponse importPlan(@PathVariable Long id, Authentication caller) {
        return PlanRunResponse.from(service.importFromWorkspace(id, caller.getName()));
    }

    /** The planning request as an external agent reads it (the text of the handoff). */
    @GetMapping(value = "/api/projects/{id}/plan/handoff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String handoff(@PathVariable Long id) {
        return service.handoffText(id);
    }

    @GetMapping("/api/plan-runs/{runId}")
    public PlanRunResponse run(@PathVariable Long runId) {
        return PlanRunResponse.from(service.run(runId));
    }

    @PostMapping("/api/projects/{id}/plan/approve")
    public ResponseEntity<ProjectResponse> approve(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean approveAllPhases,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Project project = service.approvePlan(id, approveAllPhases, Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(project.getVersion())).body(ProjectResponse.from(project));
    }

    @PostMapping("/api/phases/{phaseId}/approve")
    public ResponseEntity<PhaseResponse> approvePhase(
            @PathVariable Long phaseId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) ReviewRequest request) {
        return phase(service.reviewPhase(phaseId, true, request == null ? null : request.note(),
                Precondition.fromHeader(ifMatch)));
    }

    @PostMapping("/api/phases/{phaseId}/request-changes")
    public ResponseEntity<PhaseResponse> requestChanges(
            @PathVariable Long phaseId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) ReviewRequest request) {
        return phase(service.reviewPhase(phaseId, false, request == null ? null : request.note(),
                Precondition.fromHeader(ifMatch)));
    }

    private static ResponseEntity<PhaseResponse> phase(ProjectPhase phase) {
        return ResponseEntity.ok().eTag(ETags.of(phase.getVersion())).body(PhaseResponse.from(phase, List.of()));
    }
}
