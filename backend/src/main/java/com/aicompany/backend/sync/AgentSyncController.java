package com.aicompany.backend.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PHASE 37 (ADR-043): the agents of AI Company OS in the tools around it. Reading is
 * for whoever is signed in; writing a tool's configuration is an administrator's
 * decision (SecurityConfiguration), like what the launcher may run.
 */
@RestController
public class AgentSyncController {

    /** Both lists are optional: absent or empty means "all" -- every active agent, every tool that is available. */
    public record AgentPublishRequest(@Nullable @Size(max = 500) List<Long> agentIds,
                                 @Nullable @Size(max = 16) List<@Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$") String> tools) {
    }

    /** {@code externalIds} absent or empty brings in every agent of the tool not linked yet. */
    public record AgentImportRequest(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$") String tool,
                                @Nullable @Size(max = 200) List<@Size(max = 200) String> externalIds) {
    }

    private final AgentSyncService sync;

    public AgentSyncController(AgentSyncService sync) {
        this.sync = sync;
    }

    /** How every active agent stands in every tool, and the tools' own agents that could be brought in. */
    @GetMapping("/api/agent-sync")
    public AgentSyncService.AgentSyncOverview agentSyncOverview() {
        return sync.overview();
    }

    /** Writes the tools: each agent that is not there, or is behind, is published. One result per agent and tool. */
    @PostMapping("/api/agent-sync/publish")
    public AgentSyncService.AgentSyncReport publishAgents(@Valid @RequestBody AgentPublishRequest request, Authentication caller) {
        return sync.publish(request.agentIds(), request.tools(), caller.getName());
    }

    /** Brings a tool's own agents into AI Company OS, linked to the ones they came from. */
    @PostMapping("/api/agent-sync/import")
    public List<AgentSyncService.ImportedAgent> importAgents(@Valid @RequestBody AgentImportRequest request, Authentication caller) {
        return sync.importFrom(request.tool(), request.externalIds(), caller.getName());
    }

    /** Takes an agent out of a tool: removed there when AI Company OS made it, only unlinked when the tool had it. */
    @DeleteMapping("/api/agent-sync/{tool}/agents/{agentId}")
    public ResponseEntity<Void> unpublishAgent(@PathVariable String tool, @PathVariable Long agentId) {
        sync.unpublish(agentId, tool);
        return ResponseEntity.noContent().build();
    }
}
