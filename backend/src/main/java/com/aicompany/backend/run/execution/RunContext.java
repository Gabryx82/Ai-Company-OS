package com.aicompany.backend.run.execution;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.harness.library.Essentials;
import com.aicompany.backend.harness.library.Pertinence;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.plan.service.PlanMarkdown;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a run knows beyond the registry's three fields (ADR-021 §6, ADR-023):
 *
 * <ul>
 *   <li>the agent's own prompt engineering and harness, when the operator gave it any;</li>
 *   <li>for a <em>planned</em> task, the Human-in-the-Loop level in the system prompt
 *       and the task's and phase's documents in the message;</li>
 *   <li>PHASE 35 (ADR-041 §7): at most {@link #PERTINENT} skills or knowledge entries,
 *       those pertinent to the task, each with only its essential part.</li>
 * </ul>
 *
 * <p>The system prompt is built <strong>stable part first</strong>: role, profile, the
 * whole harness by name, what the project adopted, the autonomy rules -- the same text,
 * in the same order, for every task the agent runs in that project, so that Ollama can
 * reuse that prefix from one run to the next. Only then what depends on the task.
 *
 * <p>A task outside a plan, run by an agent without a profile, is returned exactly
 * as {@link RunPrompt#of} built it: the prompt of PHASE 6, byte for byte. That is
 * the regression the operator's accepted workflow depends on, and a test pins it.
 */
@Component
public class RunContext {

    static final int TASK_DOCUMENT_LIMIT = 12_000;
    static final int PHASE_DOCUMENT_LIMIT = 6_000;

    /** At most this many skills or knowledge entries reach a run (ADR-041 §7). */
    static final int PERTINENT = 2;

    /** Where the part that depends on the task begins. */
    static final String RELEVANT = "\n\nRelevant to this task, chosen by its keywords (the rest of your harness is named above):";

    private final ProjectWorkspaceService workspace;
    private final HarnessService harness;
    private final SkillLibrary library;
    private final int essentialLimit;
    private final int sectionChars;

    private final AgentRepository agents;

    public RunContext(ProjectWorkspaceService workspace, HarnessService harness, SkillLibrary library, AgentRepository agents,
                      @Value("${aicos.runs.skill-essential-chars:1500}") int essentialLimit,
                      @Value("${aicos.runs.task-section-chars:" + PlanMarkdown.SECTION_CHARS + "}") int sectionChars) {
        this.agents = agents;
        this.sectionChars = sectionChars;
        this.library = library;
        this.workspace = workspace;
        this.harness = harness;
        this.essentialLimit = essentialLimit;
    }

    public RunPrompt enrich(RunPrompt base, Task task, Agent agent, Project project) {
        HarnessService.AgentHarness own = agent == null ? null : harness.of(agent.getId());
        List<HarnessResource> equipped = own == null ? List.of() : own.resources();
        List<Software> software = own == null ? List.of() : own.software();
        List<HarnessResource> adopted = adoptedBeyond(project, equipped);
        String system = base.system() + agentProfile(agent, equipped, software) + projectHarness(adopted);
        StringBuilder user = new StringBuilder(base.user());
        if (project != null && task.getDocumentPath() != null) {
            system += "\n\n" + AutonomyPolicy.systemInstructions(project.getAutonomyLevel())
                    + "\nThis project keeps its context in files. The working sections of the task's document and the "
                    + "document of its phase follow the task below; follow their completion criteria.";
            // PHASE 35 (ADR-041 §10): of the task document, its working sections, the guidance compacted --
            // not its outcome history.
            append(user, project.getId(), task.getDocumentPath(), TASK_DOCUMENT_LIMIT,
                    text -> PlanMarkdown.forRun(text, sectionChars));
            if (task.getPhase() != null) {
                append(user, project.getId(), task.getPhase().getDocumentPath(), PHASE_DOCUMENT_LIMIT, text -> text);
            }
        }
        // PHASE 35: what depends on the task goes after everything that does not.
        system += pertinent(task, equipped, adopted);
        return new RunPrompt(system, user.toString());
    }

    /**
     * The operator's prompt engineering for this agent (directive §13–14), its harness by
     * name and the software it may use, or nothing. PHASE 36: every field of the agent's
     * page reaches the run -- description, capabilities, domain, the agent it reports to
     * and its context policy too, which the handoff package already carried.
     */
    private String agentProfile(Agent agent, List<HarnessResource> equipped, List<Software> software) {
        Agent parent = agent == null || agent.getParentId() == null ? null : agents.findById(agent.getParentId()).orElse(null);
        if (agent == null || (!agent.hasProfile() && equipped.isEmpty() && software.isEmpty() && parent == null
                && blank(agent.getDescription()) && blank(agent.getCapabilities()) && blank(agent.getDomain())
                && blank(agent.getContextPolicy()))) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n");
        section(text, "Description", agent.getDescription());
        section(text, "Capabilities", agent.getCapabilities());
        section(text, "Domain", agent.getDomain());
        if (parent != null) {
            text.append("\nYou are a sub-agent of ").append(parent.getName()).append(" (").append(parent.getRole())
                    .append("): you work on the part of the task it gives you, and report to it.\n");
        }
        section(text, "Agent instructions", agent.getSystemPrompt());
        section(text, "Responsibilities", agent.getResponsibilities());
        section(text, "Limits", agent.getLimits());
        section(text, "Expected output", agent.getOutputFormat());
        section(text, "Directives", agent.getDirectives());
        section(text, "Context policy", agent.getContextPolicy());
        if (!equipped.isEmpty()) {
            text.append("\nYour harness: ");
            text.append(String.join("; ", equipped.stream()
                    .map(r -> r.getKind().name().toLowerCase() + " " + r.getName()).toList()));
            text.append('.');
        }
        if (!software.isEmpty()) {
            text.append("\nSoftware you may use: ")
                    .append(String.join("; ", software.stream().map(Software::getName).toList())).append('.');
        }
        return text.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * PHASE 34: what the project adopted holds for every agent working on it, named
     * after the agent's own and without repeating them. Nothing when the project
     * adopted nothing new for this agent, so a run without adoptions keeps its prompt.
     */
    private static String projectHarness(List<HarnessResource> adopted) {
        if (adopted.isEmpty()) {
            return "";
        }
        return "\n\nAdopted by this project, for every agent working on it: "
                + String.join("; ", adopted.stream().map(r -> r.getKind().name().toLowerCase() + " " + r.getName()).toList())
                + '.';
    }

    private List<HarnessResource> adoptedBeyond(Project project, List<HarnessResource> equipped) {
        if (project == null) {
            return List.of();
        }
        Set<String> carried = new HashSet<>();
        equipped.forEach(r -> carried.add(r.getKey()));
        return harness.ofProject(project.getId()).stream().filter(r -> !carried.contains(r.getKey())).toList();
    }

    /**
     * PHASE 35 (ADR-041 §7, revises ADR-026 §3): of the agent's and the project's skills
     * and knowledge, at most {@link #PERTINENT} pertinent to the task -- by
     * {@link Pertinence} -- each with its essential part ({@link Essentials}). Nothing
     * when none is pertinent.
     */
    private String pertinent(Task task, List<HarnessResource> equipped, List<HarnessResource> adopted) {
        List<HarnessResource> candidates = new ArrayList<>();
        equipped.stream().filter(r -> SkillLibrary.fileBacked(r.getKind())).forEach(candidates::add);
        adopted.stream().filter(r -> SkillLibrary.fileBacked(r.getKind())).forEach(candidates::add);
        if (candidates.isEmpty()) {
            return "";
        }
        String taskText = task.getTitle() + "\n" + (task.getDescription() == null ? "" : task.getDescription());
        StringBuilder text = new StringBuilder();
        for (HarnessResource r : Pertinence.select(taskText, candidates, PERTINENT)) {
            Optional<String> essential = library.body(r).flatMap(body -> Essentials.of(body, r.getDescription(), essentialLimit))
                    .or(() -> Essentials.of("", r.getDescription(), essentialLimit));
            essential.ifPresent(part -> text.append("\n\n")
                    .append(r.getKind() == HarnessResource.Kind.SKILL ? "Skill" : "Knowledge")
                    .append(" \"").append(r.getName()).append("\":\n").append(part));
        }
        return text.isEmpty() ? "" : RELEVANT + text;
    }

    private static void section(StringBuilder text, String title, String body) {
        if (body != null && !body.isBlank()) {
            text.append('\n').append(title).append(":\n").append(body.strip()).append('\n');
        }
    }

    private void append(StringBuilder user, Long projectId, String path, int limit,
                        java.util.function.UnaryOperator<String> excerpt) {
        Optional<String> text;
        try {
            text = workspace.readText(projectId, path).map(excerpt);
        } catch (RuntimeException unavailable) {
            return; // no folder, or a path refused: the run goes on with what the database knows
        }
        text.ifPresent(content -> user.append("\n\n--- ").append(path).append(" ---\n")
                .append(content.length() <= limit ? content : content.substring(0, limit) + "\n[…troncato]"));
    }
}
