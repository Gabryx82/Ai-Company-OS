package com.aicompany.backend.run.execution;

import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ADR-021 §6: a run of a <em>planned</em> task carries the project's context from
 * its files -- the Human-in-the-Loop level in the system prompt, the task's and
 * its phase's documents in the message.
 *
 * <p>A task outside a plan is returned exactly as {@link RunPrompt#of} built it:
 * the prompt of PHASE 6, byte for byte. That is the regression the operator's
 * accepted workflow depends on, and a test pins it.
 */
@Component
public class RunContext {

    static final int TASK_DOCUMENT_LIMIT = 12_000;
    static final int PHASE_DOCUMENT_LIMIT = 6_000;

    private final ProjectWorkspaceService workspace;

    public RunContext(ProjectWorkspaceService workspace) {
        this.workspace = workspace;
    }

    public RunPrompt enrich(RunPrompt base, Task task, Project project) {
        if (project == null || task.getDocumentPath() == null) {
            return base;
        }
        String system = base.system() + "\n\n" + AutonomyPolicy.systemInstructions(project.getAutonomyLevel())
                + "\nThis project keeps its context in files. The documents of the task and of its phase follow "
                + "the task below; follow their completion criteria.";
        StringBuilder user = new StringBuilder(base.user());
        append(user, project.getId(), task.getDocumentPath(), TASK_DOCUMENT_LIMIT);
        if (task.getPhase() != null) {
            append(user, project.getId(), task.getPhase().getDocumentPath(), PHASE_DOCUMENT_LIMIT);
        }
        return new RunPrompt(system, user.toString());
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
