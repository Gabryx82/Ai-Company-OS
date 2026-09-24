package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.orchestrator.model.TaskHandoff;
import com.aicompany.backend.orchestrator.model.TaskReview;
import com.aicompany.backend.orchestrator.repository.TaskHandoffRepository;
import com.aicompany.backend.orchestrator.repository.TaskReviewRepository;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.service.PlanMarkdown;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.task.exception.IllegalTaskStateTransitionException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.exception.WorkspaceNotConfiguredException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

/**
 * The doing half of the Master Orchestrator (ADR-021 §4–5): hand a task to an
 * external agent, and record what the operator decided about the outcome.
 *
 * <p>Both follow the lock protocol every task write follows (ADR-006, ADR-009):
 * the task row first, its tag, then its project and agent shared.
 */
@Service
@Transactional
public class ExecutionService {

    public record HandoffResult(TaskHandoff handoff, Task task, String prompt, boolean promptToClipboard) {
    }

    public record ReviewResult(TaskReview review, Task task) {
    }

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final AgentRepository agents;
    private final SoftwareRepository softwareCatalog;
    private final SoftwareService software;
    private final ProjectWorkspaceService workspace;
    private final TaskHandoffRepository handoffs;
    private final TaskReviewRepository reviews;

    public ExecutionService(TaskRepository tasks, ProjectRepository projects, AgentRepository agents,
                            SoftwareRepository softwareCatalog, SoftwareService software,
                            ProjectWorkspaceService workspace, TaskHandoffRepository handoffs,
                            TaskReviewRepository reviews) {
        this.tasks = tasks;
        this.projects = projects;
        this.agents = agents;
        this.softwareCatalog = softwareCatalog;
        this.software = software;
        this.workspace = workspace;
        this.handoffs = handoffs;
        this.reviews = reviews;
    }

    /**
     * Writes {@code .aicos/handoffs/TASK-NNN-<target>.md}, opens the target in the
     * project folder, and starts the task if it is open. Refused -- before anything
     * is written or launched -- by the same rules as a run: frozen, done, no
     * active agent, phase not approved (ADR-022).
     */
    public HandoffResult handoff(Long taskId, String targetKey, String requestedBy, Precondition precondition) {
        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());
        Project project = task.getProjectId() == null ? null : projects.findByIdForShare(task.getProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(task.getProjectId()));
        Agent agent = task.getAgentId() == null ? null : agents.findByIdForShare(task.getAgentId())
                .orElseThrow(() -> new AgentNotFoundException(task.getAgentId()));

        task.requireRunnable(project, agent);
        if (project == null || task.getCode() == null || task.getDocumentPath() == null) {
            throw new SoftwareNotLaunchableException(
                    "Only a task of a project plan can be handed off: it needs its TASK-NNN document");
        }
        Path folder = workspace.folderOf(project.getId()).orElseThrow(() -> new WorkspaceNotConfiguredException(
                "The folder of project " + project.getId() + " does not exist: prepare the workspace first"));
        Software target = softwareCatalog.findByKey(targetKey)
                .filter(Software::isExecutionTarget)
                .orElseThrow(() -> new RequestValidationException("target",
                        "'" + targetKey + "' is not an execution target of the Software Hub"));

        String document = ".aicos/handoffs/" + task.getCode() + "-" + target.getKey() + ".md";
        String prompt = "Leggi " + document + " ed esegui la task che descrive.";
        workspace.write(project.getId(), document, handoffDocument(task, project, agent, target));

        if (task.getStatus() == TaskStatus.OPEN) {
            task.apply(TaskTransition.START, project, agent);
        }
        markPhaseStarted(task.getPhase());

        List<String> arguments = target.getLaunchKind() == LaunchKind.CLI ? cliArguments(target, prompt) : List.of();
        LaunchPlan launched = software.launchIn(target.getKey(), folder, arguments);
        TaskHandoff handoff = handoffs.save(new TaskHandoff(taskId, target.getKey(), document, prompt,
                String.join(" ", launched.command()), requestedBy));
        tasks.saveAndFlush(task);
        return new HandoffResult(handoff, task, prompt, target.getLaunchKind() != LaunchKind.CLI);
    }

    /**
     * The operator's verdict. {@code ACCEPTED} completes a task in progress;
     * {@code CHANGES_REQUESTED} keeps it in progress, or reopens it if it was done.
     */
    public ReviewResult review(Long taskId, TaskReview.Verdict verdict, String note, Long runId, Long handoffId,
                               String reviewer, Precondition precondition) {
        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());
        Project project = task.getProjectId() == null ? null : projects.findByIdForShare(task.getProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(task.getProjectId()));

        if (verdict == TaskReview.Verdict.ACCEPTED) {
            if (task.getStatus() != TaskStatus.IN_PROGRESS) {
                throw new IllegalTaskStateTransitionException(task.getStatus(), TaskTransition.COMPLETE);
            }
            task.apply(TaskTransition.COMPLETE, project, null);
            markPhaseDoneIfComplete(task);
        } else if (task.getStatus() == TaskStatus.DONE) {
            task.apply(TaskTransition.REOPEN, project, null);
        }
        TaskReview review = reviews.save(new TaskReview(taskId, verdict, note, runId, handoffId, reviewer));
        tasks.saveAndFlush(task);
        return new ReviewResult(review, task);
    }

    @Transactional(readOnly = true)
    public List<TaskHandoff> handoffs(Long taskId) {
        return handoffs.findAllByTaskIdOrderByIdDesc(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskReview> reviews(Long taskId) {
        return reviews.findAllByTaskIdOrderByIdDesc(taskId);
    }

    // --- helpers -------------------------------------------------------------------

    /** How each CLI takes an initial prompt. The prompt is the control plane's own text (ADR-019 I3). */
    static List<String> cliArguments(Software target, String prompt) {
        String command = target.getCliCommand().strip().split("\\s+")[0];
        return switch (command) {
            case "opencode" -> List.of("--prompt", prompt);
            default -> List.of(prompt);
        };
    }

    private static String handoffDocument(Task task, Project project, Agent agent, Software target) {
        return """
                # Handoff — %s → %s

                > Preparato dal Master Orchestrator di AI Company OS per il progetto «%s».
                > Agente responsabile: %s (%s).

                ## Prompt

                %s

                ## Contesto da leggere, in quest'ordine

                1. `AGENTS.md` — la governance del progetto
                2. `%s` — la task: obiettivo, scope, file, test, criteri di completamento
                3. `%s` — la fase a cui appartiene
                4. solo i file che la task elenca

                ## Livello di Human-in-the-Loop: %s

                %s

                ## Alla fine

                - Compila la sezione **Esito** di `%s`: cosa hai fatto, file toccati, test eseguiti, cosa resta.
                - Non considerare chiusa la task: la chiude l'operatore dopo la review in AI Company OS.
                """.formatted(task.getCode(), target.getName(), project.getName(), agent.getName(), agent.getRole(),
                PlanMarkdown.compactPrompt(task.getCode()), task.getDocumentPath(),
                task.getPhase() == null ? "docs/IMPLEMENTATION_PLAN.md" : task.getPhase().getDocumentPath(),
                AutonomyPolicy.label(project.getAutonomyLevel()), AutonomyPolicy.rules(project.getAutonomyLevel()),
                task.getDocumentPath());
    }

    private static void markPhaseStarted(ProjectPhase phase) {
        if (phase != null && phase.getStatus() == ProjectPhase.Status.PLANNED) {
            phase.moveTo(ProjectPhase.Status.IN_PROGRESS);
        }
    }

    private void markPhaseDoneIfComplete(Task completed) {
        ProjectPhase phase = completed.getPhase();
        if (phase == null) {
            return;
        }
        boolean allDone = tasks.findAllByProjectId(completed.getProjectId()).stream()
                .filter(t -> phase.getId().equals(t.getPhaseId()))
                .allMatch(t -> t.getId().equals(completed.getId()) || t.getStatus() == TaskStatus.DONE);
        phase.moveTo(allDone ? ProjectPhase.Status.DONE : ProjectPhase.Status.IN_PROGRESS);
    }
}
