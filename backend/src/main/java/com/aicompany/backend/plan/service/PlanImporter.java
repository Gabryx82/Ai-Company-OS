package com.aicompany.backend.plan.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentStatus;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.agent.routing.AgentRouter;
import com.aicompany.backend.plan.exception.PlanLockedException;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.repository.ProjectPhaseRepository;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.PlanStatus;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.repository.TaskRunRepository;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a validated {@link PlanDocument} into phases and tasks, and writes the
 * documents that describe them (ADR-021 §3). One transaction: either the whole
 * plan is in the index, or none of it.
 *
 * <p><strong>Replacing a plan.</strong> A draft can be regenerated -- that is
 * what drafts are for -- but only while it is still a draft of <em>intent</em>:
 * the plan is not approved, and no task of it has left {@code OPEN} or has ever
 * been run. Past that point the plan describes work, and it is locked. The rows
 * removed on a replacement are the planner's own, created by the previous draft
 * and never touched since.
 */
@Service
public class PlanImporter {

    public record Imported(int phases, int tasks) {
    }

    private final ProjectRepository projects;
    private final ProjectPhaseRepository phases;
    private final TaskRepository tasks;
    private final TaskRunRepository runs;
    private final AgentRepository agents;
    private final AgentRouter router;
    private final ProjectWorkspaceService workspace;

    public PlanImporter(ProjectRepository projects, ProjectPhaseRepository phases, TaskRepository tasks,
                        TaskRunRepository runs, AgentRepository agents, AgentRouter router,
                        ProjectWorkspaceService workspace) {
        this.projects = projects;
        this.phases = phases;
        this.tasks = tasks;
        this.runs = runs;
        this.agents = agents;
        this.router = router;
        this.workspace = workspace;
    }

    @Transactional
    public Imported importPlan(Long projectId, PlanDocument plan, String source) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        if (project.getPlanStatus() == PlanStatus.APPROVED) {
            throw new PlanLockedException("The plan of project " + projectId + " is approved; it can no longer be replaced");
        }
        replaceDraft(project);

        List<Agent> active = agents.findAllByStatusOrderByIdAsc(AgentStatus.ACTIVE);
        List<PlanMarkdown.PhaseRef> phaseRefs = new ArrayList<>();
        int number = 0;
        int taskNumber = 0;
        for (PlanDocument.PhasePlan phasePlan : plan.phases()) {
            number++;
            String phaseDocument = "docs/phases/PHASE_" + number + ".md";
            ProjectPhase phase = phases.save(new ProjectPhase(project, number, phasePlan.title(),
                    phasePlan.objective(), phaseDocument));
            List<PlanMarkdown.TaskRef> taskRefs = new ArrayList<>();
            for (PlanDocument.TaskPlan taskPlan : phasePlan.tasks()) {
                taskNumber++;
                String code = "TASK-%03d".formatted(taskNumber);
                String document = "tasks/" + code + ".md";
                Task task = new Task(taskPlan.title(), description(taskPlan), TaskStatus.OPEN,
                        TaskPriority.valueOf(taskPlan.priority()));
                task.assignTo(project);
                task.attachToPlan(phase, code, document);
                Optional<Agent> agent = pick(taskPlan, active);
                agent.ifPresent(a -> task.assignTo(a, project));
                tasks.save(task);
                taskRefs.add(new PlanMarkdown.TaskRef(code, document, agent.map(Agent::getName).orElse(null), taskPlan));
            }
            phaseRefs.add(new PlanMarkdown.PhaseRef(number, phaseDocument, phasePlan, taskRefs));
        }
        project.markPlan(PlanStatus.DRAFT);
        projects.flush();

        workspace.write(projectId, ".aicos/plan.json", plan.toJson());
        workspace.write(projectId, "docs/IMPLEMENTATION_PLAN.md",
                PlanMarkdown.implementationPlan(project, plan, phaseRefs, source));
        for (PlanMarkdown.PhaseRef phaseRef : phaseRefs) {
            workspace.write(projectId, phaseRef.documentPath(), PlanMarkdown.phase(project, phaseRef));
            for (PlanMarkdown.TaskRef taskRef : phaseRef.tasks()) {
                workspace.write(projectId, taskRef.documentPath(), PlanMarkdown.task(project, phaseRef, taskRef));
            }
        }
        workspace.refreshManifest(projectId);
        return new Imported(phaseRefs.size(), taskNumber);
    }

    private void replaceDraft(Project project) {
        List<ProjectPhase> existing = phases.findByProject(project.getId());
        if (existing.isEmpty()) {
            return;
        }
        List<Task> planned = tasks.findAllByProjectId(project.getId()).stream()
                .filter(task -> task.getPhase() != null)
                .toList();
        for (Task task : planned) {
            if (task.getStatus() != TaskStatus.OPEN || runs.existsByTaskId(task.getId())) {
                throw new PlanLockedException("Task " + task.getCode() + " of the current plan has already been "
                        + "worked on; the plan can no longer be replaced");
            }
        }
        tasks.deleteAll(planned);
        tasks.flush();
        phases.deleteAll(existing);
        phases.flush();
    }

    /**
     * The agent a planned task asks for: its role named exactly, then a role that
     * contains or is contained in it, then the lexical router on the whole task.
     * No match leaves the task unassigned -- the operator assigns it.
     */
    Optional<Agent> pick(PlanDocument.TaskPlan task, List<Agent> active) {
        String role = task.agentRole() == null ? "" : task.agentRole().strip().toLowerCase(Locale.ROOT);
        if (!role.isEmpty()) {
            for (Agent agent : active) {
                if (agent.getRole().equalsIgnoreCase(role) || agent.getName().equalsIgnoreCase(role)) {
                    return Optional.of(agent);
                }
            }
            for (Agent agent : active) {
                String candidate = agent.getRole().toLowerCase(Locale.ROOT);
                if (candidate.contains(role) || role.contains(candidate)) {
                    return Optional.of(agent);
                }
            }
        }
        return router.suggest(String.join(" ", role, task.title(), task.objective() == null ? "" : task.objective()))
                .stream()
                .filter(suggestion -> suggestion.score() > 0)
                .findFirst()
                .flatMap(suggestion -> active.stream().filter(a -> a.getId().equals(suggestion.agentId())).findFirst());
    }

    private static String description(PlanDocument.TaskPlan task) {
        StringBuilder text = new StringBuilder();
        if (task.objective() != null) {
            text.append(task.objective());
        }
        if (task.scope() != null) {
            text.append(text.isEmpty() ? "" : "\n\n").append("Scope: ").append(task.scope());
        }
        String value = text.toString();
        return value.length() > 4900 ? value.substring(0, 4899) + "…" : (value.isEmpty() ? null : value);
    }
}
