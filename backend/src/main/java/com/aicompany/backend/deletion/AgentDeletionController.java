package com.aicompany.backend.deletion;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deleting agents (PHASE 30, ADR-035 §2). The deletes are admin-only in
 * SecurityConfiguration, like every delete that destroys data; the previews are
 * readable by whoever can read the agents, so the console shows what a delete
 * would take before anybody decides.
 *
 * <p>{@code subagents} defaults to {@code DETACH}: it destroys nothing.
 * {@code DELETE} must be asked for. {@code inProgress} defaults to {@code BLOCK}:
 * stopping work in progress must be asked for too.
 */
@RestController
public class AgentDeletionController {

    /** One agent of a selection, with the version the caller read (its ETag without quotes). */
    public record AgentVersion(@NotNull Long id, @NotNull @PositiveOrZero Long version) {
    }

    public record BulkAgentDelete(@NotEmpty @Size(max = 500) List<@Valid @NotNull AgentVersion> agents,
                                  AgentDeletionService.SubAgents subagents, AgentDeletionService.InProgress inProgress) {
    }

    private final AgentDeletionService deletions;

    public AgentDeletionController(AgentDeletionService deletions) {
        this.deletions = deletions;
    }

    @GetMapping("/api/agents/{id}/deletion")
    public DeletionPreview preview(@PathVariable Long id,
                                   @RequestParam(defaultValue = "DETACH") AgentDeletionService.SubAgents subagents,
                                   @RequestParam(defaultValue = "BLOCK") AgentDeletionService.InProgress inProgress) {
        return deletions.preview(List.of(id), subagents, inProgress);
    }

    /** The preview of a selection: {@code ?ids=3,5,8}. */
    @GetMapping("/api/agents/deletion")
    public DeletionPreview previewSelection(@RequestParam List<Long> ids,
                                            @RequestParam(defaultValue = "DETACH") AgentDeletionService.SubAgents subagents,
                                            @RequestParam(defaultValue = "BLOCK") AgentDeletionService.InProgress inProgress) {
        return deletions.preview(ids, subagents, inProgress);
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable Long id,
                                       @RequestParam(defaultValue = "DETACH") AgentDeletionService.SubAgents subagents,
                                       @RequestParam(defaultValue = "BLOCK") AgentDeletionService.InProgress inProgress,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       Authentication caller) {
        deletions.delete(id, subagents, inProgress, caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /**
     * Several agents in one transaction: all of them, or none (a 404, 409 or 412
     * on one refuses the batch). A command resource rather than a DELETE with a
     * body, which proxies and clients are allowed to drop.
     */
    @PostMapping("/api/agents/bulk-delete")
    public ResponseEntity<Void> deleteSelection(@Valid @RequestBody BulkAgentDelete body, Authentication caller) {
        Map<Long, Precondition> selection = new LinkedHashMap<>();
        for (AgentVersion agent : body.agents()) {
            if (selection.put(agent.id(), Precondition.fromHeader(ETags.of(agent.version()))) != null) {
                throw new com.aicompany.backend.api.RequestValidationException("agents", "must not repeat an agent");
            }
        }
        deletions.deleteAll(selection, body.subagents() == null ? AgentDeletionService.SubAgents.DETACH : body.subagents(),
                body.inProgress() == null ? AgentDeletionService.InProgress.BLOCK : body.inProgress(), caller.getName());
        return ResponseEntity.noContent().build();
    }
}
