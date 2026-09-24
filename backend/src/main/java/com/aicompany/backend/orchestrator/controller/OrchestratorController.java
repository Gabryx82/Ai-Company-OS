package com.aicompany.backend.orchestrator.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.orchestrator.model.TaskHandoff;
import com.aicompany.backend.orchestrator.model.TaskReview;
import com.aicompany.backend.orchestrator.service.ExecutionService;
import com.aicompany.backend.orchestrator.service.OrchestrationService;
import com.aicompany.backend.task.dto.TaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The Master Orchestrator's decision, handoffs and reviews for one task (ADR-021 §3–5). */
@RestController
public class OrchestratorController {

    public record HandoffRequest(@NotBlank @Size(max = 64) String target) {
    }

    public record ReviewRequest(@NotNull TaskReview.Verdict verdict, @Size(max = 4000) String note,
                                Long runId, Long handoffId) {
    }

    public record HandoffResponse(Long id, Long taskId, String target, String documentPath, String prompt,
                                  String command, String requestedBy, Instant createdAt) {
        public static HandoffResponse from(TaskHandoff h) {
            return new HandoffResponse(h.getId(), h.getTaskId(), h.getTarget(), h.getDocumentPath(), h.getPrompt(),
                    h.getCommand(), h.getRequestedBy(), h.getCreatedAt());
        }
    }

    public record HandoffResult(HandoffResponse handoff, TaskResponse task, String prompt, String fullPrompt,
                                String delivery, String folder, String openUrl, List<String> written,
                                boolean promptToClipboard) {
    }

    public record ReviewResponse(Long id, Long taskId, TaskReview.Verdict verdict, String note, Long runId,
                                 Long handoffId, String reviewer, Instant createdAt) {
        public static ReviewResponse from(TaskReview r) {
            return new ReviewResponse(r.getId(), r.getTaskId(), r.getVerdict(), r.getNote(), r.getRunId(),
                    r.getHandoffId(), r.getReviewer(), r.getCreatedAt());
        }
    }

    public record ReviewResult(ReviewResponse review, TaskResponse task) {
    }

    private final OrchestrationService orchestration;
    private final ExecutionService execution;

    public OrchestratorController(OrchestrationService orchestration, ExecutionService execution) {
        this.orchestration = orchestration;
        this.execution = execution;
    }

    /** What the orchestrator would do with this task, and why. Changes nothing. */
    @GetMapping("/api/tasks/{id}/orchestration")
    public OrchestrationService.Orchestration decide(@PathVariable Long id) {
        return orchestration.decide(id);
    }

    /** Hands the task to an external agent. Takes the task's tag: it may start the task. */
    @PostMapping("/api/tasks/{id}/handoffs")
    public ResponseEntity<HandoffResult> handoff(@PathVariable Long id,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody HandoffRequest request, Authentication caller) {
        ExecutionService.HandoffResult result = execution.handoff(id, request.target(), caller.getName(),
                Precondition.fromHeader(ifMatch));
        return ResponseEntity.accepted().eTag(ETags.of(result.task().getVersion()))
                .body(new HandoffResult(HandoffResponse.from(result.handoff()), TaskResponse.from(result.task()),
                        result.prompt(), result.fullPrompt(), result.delivery(), result.folder(), result.openUrl(),
                        result.written(), result.promptToClipboard()));
    }

    @GetMapping("/api/tasks/{id}/handoffs")
    public List<HandoffResponse> handoffs(@PathVariable Long id) {
        return execution.handoffs(id).stream().map(HandoffResponse::from).toList();
    }

    /** The operator's verdict on an outcome. Takes the task's tag: it may complete or reopen it. */
    @PostMapping("/api/tasks/{id}/reviews")
    public ResponseEntity<ReviewResult> review(@PathVariable Long id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody ReviewRequest request, Authentication caller) {
        ExecutionService.ReviewResult result = execution.review(id, request.verdict(), request.note(),
                request.runId(), request.handoffId(), caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(result.task().getVersion()))
                .body(new ReviewResult(ReviewResponse.from(result.review()), TaskResponse.from(result.task())));
    }

    @GetMapping("/api/tasks/{id}/reviews")
    public List<ReviewResponse> reviews(@PathVariable Long id) {
        return execution.reviews(id).stream().map(ReviewResponse::from).toList();
    }
}
