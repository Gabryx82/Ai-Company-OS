package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.ExecutionTargetCatalog;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.orchestrator.model.TaskHandoff;
import com.aicompany.backend.orchestrator.model.TaskReview;
import com.aicompany.backend.orchestrator.repository.TaskHandoffRepository;
import com.aicompany.backend.orchestrator.repository.TaskReviewRepository;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.task.exception.IllegalTaskStateTransitionException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
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

    public record HandoffResult(TaskHandoff handoff, Task task, String prompt, String fullPrompt, String delivery,
                                String folder, String openUrl, List<String> written, boolean promptToClipboard) {
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
    private final ExecutionTargetCatalog targets;
    private final AgentBindingService binding;
    private final HarnessService harness;
    private final SkillLibrary library;

    public ExecutionService(TaskRepository tasks, ProjectRepository projects, AgentRepository agents,
                            SoftwareRepository softwareCatalog, SoftwareService software,
                            ProjectWorkspaceService workspace, TaskHandoffRepository handoffs,
                            TaskReviewRepository reviews, ExecutionTargetCatalog targets,
                            AgentBindingService binding, HarnessService harness, SkillLibrary library) {
        this.library = library;
        this.targets = targets;
        this.binding = binding;
        this.harness = harness;
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
     * Hands a task to an application or a CLI (ADR-021 §5, ADR-025 §4), with no
     * API in between: prepares the working folder (the project's, created if
     * missing, or an inbox folder for a task without a project), the task
     * document, the tool's own rules file, and the package
     * {@code .aicos/handoffs/<TASK>-<target>.md} with role, skills, context and
     * Human-in-the-Loop rules; then opens the tool as its delivery says, and
     * starts the task if it is open. Refused -- before anything is written or
     * launched -- by the same rules as a run: frozen, done, no active agent,
     * phase not approved (ADR-022).
     */
    public HandoffResult handoff(Long taskId, String targetKey, String requestedBy, Precondition precondition) {
        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());
        Project project = task.getProjectId() == null ? null : projects.findByIdForShare(task.getProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(task.getProjectId()));
        Agent agent = task.getAgentId() == null ? null : agents.findByIdForShare(task.getAgentId())
                .orElseThrow(() -> new AgentNotFoundException(task.getAgentId()));

        task.requireRunnable(project, agent);
        ExecutionTarget target = targets.find(targetKey).filter(t -> !t.isEngine())
                .orElseThrow(() -> new RequestValidationException("target",
                        "'" + targetKey + "' is not an execution target a task can be handed off to"));

        // Where: the project's folder -- prepared if it does not exist yet -- or an inbox folder.
        Path folder;
        if (project != null) {
            folder = workspace.workspaceOf(project.getId());
            if (!Files.isDirectory(folder)) {
                workspace.scaffold(project.getId());
            }
        } else {
            folder = workspace.inbox(task.getId());
        }
        String label = HandoffPackager.label(task);
        String packagePath = ".aicos/handoffs/" + label + "-" + target.key() + ".md";
        List<String> written = new ArrayList<>();

        String taskDocument = project != null && task.getDocumentPath() != null ? task.getDocumentPath()
                : project != null ? "tasks/" + label + ".md" : "TASK.md";
        if (workspace.readIn(folder, taskDocument).isEmpty()) {
            workspace.writeIn(folder, taskDocument, HandoffPackager.adHocTaskDocument(task, project, agent));
            written.add(taskDocument);
        }
        if (project == null) {
            for (String governance : List.of("AGENTS.md", "CLAUDE.md")) {
                writeIfOurs(folder, governance, HandoffPackager.inboxGovernance(packagePath), written);
            }
        }
        if (target.contextFile() != null && !List.of("AGENTS.md", "CLAUDE.md").contains(target.contextFile())) {
            writeIfOurs(folder, target.contextFile(), HandoffPackager.targetRules(target, packagePath), written);
        }

        List<com.aicompany.backend.harness.model.HarnessResource> equipped = harness.of(agent.getId()).resources();
        java.util.Map<String, String> skillFiles = new java.util.LinkedHashMap<>();
        for (var r : equipped) {
            if (SkillLibrary.fileBacked(r.getKind()) && library.body(r).isPresent()) {
                skillFiles.put(r.getKey(), library.root().resolve(SkillLibrary.relativePath(r.getKind(), r.getKey())).toString());
            }
        }
        HandoffPackager.Package pkg = HandoffPackager.build(task, project, agent, equipped,
                binding.describe(agent), target, folder, taskDocument,
                workspace.readIn(folder, taskDocument).orElse(null), packagePath, skillFiles);
        workspace.writeIn(folder, packagePath, pkg.document());
        written.add(packagePath);

        if (task.getStatus() == TaskStatus.OPEN) {
            task.apply(TaskTransition.START, project, agent);
        }
        markPhaseStarted(task.getPhase());

        String command = null;
        String openUrl = null;
        switch (target.delivery()) {
            case CLI_PROMPT -> {
                Software cli = softwareOf(target);
                command = String.join(" ", software.launchIn(cli.getKey(), folder,
                        cliArguments(cli, pkg.compactPrompt())).command());
            }
            case IDE_FOLDER, APP_PASTE -> command = String.join(" ",
                    software.launchIn(softwareOf(target).getKey(), folder, List.of()).command());
            case WEB_PASTE -> {
                openUrl = softwareOf(target).getUrl();
                command = openUrl;
            }
            default -> {
                // MANUAL: the package is written, nothing is opened.
            }
        }
        String recorded = project != null ? packagePath : folder.resolve(packagePath).toString();
        TaskHandoff handoff = handoffs.save(new TaskHandoff(taskId, target.key(), recorded, pkg.compactPrompt(),
                command, requestedBy));
        tasks.saveAndFlush(task);
        return new HandoffResult(handoff, task, pkg.compactPrompt(), pkg.fullPrompt(), target.delivery().name(),
                folder.toString(), openUrl, List.copyOf(written),
                target.delivery() != ExecutionTargetCatalog.Delivery.CLI_PROMPT);
    }

    /** Writes a generated file, unless the operator wrote their own there. */
    private void writeIfOurs(Path folder, String relative, String content, List<String> written) {
        Optional<String> existing = workspace.readIn(folder, relative);
        if (existing.isEmpty() || existing.get().startsWith(HandoffPackager.MARKER)) {
            workspace.writeIn(folder, relative, content);
            written.add(relative);
        }
    }

    private Software softwareOf(ExecutionTarget target) {
        return softwareCatalog.findByKey(target.software())
                .orElseThrow(() -> new SoftwareNotLaunchableException("The software of " + target.name()
                        + " ('" + target.software() + "') is not in the Software Hub"));
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
