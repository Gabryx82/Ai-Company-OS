package com.aicompany.backend.run.execution;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * What a run knows beyond the registry's three fields (ADR-021 §6, ADR-023):
 *
 * <ul>
 *   <li>the agent's own prompt engineering and harness, when the operator gave it any;</li>
 *   <li>for a <em>planned</em> task, the Human-in-the-Loop level in the system prompt
 *       and the task's and phase's documents in the message.</li>
 * </ul>
 *
 * A task outside a plan, run by an agent without a profile, is returned exactly
 * as {@link RunPrompt#of} built it: the prompt of PHASE 6, byte for byte. That is
 * the regression the operator's accepted workflow depends on, and a test pins it.
 */
@Component
public class RunContext {

    static final int TASK_DOCUMENT_LIMIT = 12_000;
    static final int PHASE_DOCUMENT_LIMIT = 6_000;

    private final ProjectWorkspaceService workspace;
    private final HarnessService harness;

    public RunContext(ProjectWorkspaceService workspace, HarnessService harness) {
        this.workspace = workspace;
        this.harness = harness;
    }

    public RunPrompt enrich(RunPrompt base, Task task, Agent agent, Project project) {
        String system = base.system() + agentProfile(agent);
        StringBuilder user = new StringBuilder(base.user());
        if (project != null && task.getDocumentPath() != null) {
            system += "\n\n" + AutonomyPolicy.systemInstructions(project.getAutonomyLevel())
                    + "\nThis project keeps its context in files. The documents of the task and of its phase follow "
                    + "the task below; follow their completion criteria.";
            append(user, project.getId(), task.getDocumentPath(), TASK_DOCUMENT_LIMIT);
            if (task.getPhase() != null) {
                append(user, project.getId(), task.getPhase().getDocumentPath(), PHASE_DOCUMENT_LIMIT);
            }
        }
        return new RunPrompt(system, user.toString());
    }

    /** The operator's prompt engineering for this agent (directive §13–14), or nothing. */
    private String agentProfile(Agent agent) {
        if (agent == null || !agent.hasProfile()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n");
        section(text, "Agent instructions", agent.getSystemPrompt());
        section(text, "Responsibilities", agent.getResponsibilities());
        section(text, "Limits", agent.getLimits());
        section(text, "Expected output", agent.getOutputFormat());
        section(text, "Directives", agent.getDirectives());
        List<HarnessResource> equipped = harness.of(agent.getId()).resources();
        if (!equipped.isEmpty()) {
            text.append("\nYour harness: ");
            text.append(String.join("; ", equipped.stream()
                    .map(r -> r.getKind().name().toLowerCase() + " " + r.getName()).toList()));
            text.append('.');
        }
        return text.toString();
    }

    private static void section(StringBuilder text, String title, String body) {
        if (body != null && !body.isBlank()) {
            text.append('\n').append(title).append(":\n").append(body.strip()).append('\n');
        }
    }

    private void append(StringBuilder user, Long projectId, String path, int limit) {
        Optional<String> text;
        try {
            text = workspace.readText(projectId, path);
        } catch (RuntimeException unavailable) {
            return; // no folder, or a path refused: the run goes on with what the database knows
        }
        text.ifPresent(content -> user.append("\n\n--- ").append(path).append(" ---\n")
                .append(content.length() <= limit ? content : content.substring(0, limit) + "\n[…troncato]"));
    }
}
