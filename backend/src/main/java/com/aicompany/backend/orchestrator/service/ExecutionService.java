package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.ExecutionTargetCatalog;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.harness.hub.SkillHubService;
import com.aicompany.backend.harness.library.Pertinence;
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
import com.aicompany.backend.task.model.TaskTransitionRecord;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskTransitions;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.jspecify.annotations.Nullable;
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
                                String folder, @Nullable String openUrl, List<String> written, boolean promptToClipboard,
                                @Nullable String notOpened) {
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
    private final TaskTransitions transitions;

    @PersistenceContext
    private EntityManager entityManager;

    private final OpenCodeSessions sessions;
    private final HandoffReads reads;
    private final SkillHubService hub;
    private final ReadinessService readiness;
    private final com.aicompany.backend.sync.AgentPublications publications;
    private final com.aicompany.backend.journal.WorkChanges changes;

    public ExecutionService(TaskRepository tasks, ProjectRepository projects, AgentRepository agents,
                            SoftwareRepository softwareCatalog, SoftwareService software,
                            ProjectWorkspaceService workspace, TaskHandoffRepository handoffs,
                            TaskReviewRepository reviews, ExecutionTargetCatalog targets,
                            AgentBindingService binding, HarnessService harness, SkillLibrary library,
                            TaskTransitions transitions, OpenCodeSessions sessions, HandoffReads reads,
                            SkillHubService hub, ReadinessService readiness,
                            com.aicompany.backend.sync.AgentPublications publications,
                            com.aicompany.backend.journal.WorkChanges changes) {
        this.publications = publications;
        this.changes = changes;
        this.readiness = readiness;
        this.hub = hub;
        this.reads = reads;
        this.sessions = sessions;
        this.transitions = transitions;
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
     *
     * @param learning PHASE 37 (ADR-044): whether the agent teaches (a lesson the operator copies from), works
     *                 alone and leaves a lesson, or neither; null for what the project's level implies
     */
    public HandoffResult handoff(Long taskId, String targetKey, com.aicompany.backend.hitl.@Nullable LearningMode learning,
                                 String requestedBy, Precondition precondition) {
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
        // PHASE 36 (ADR-042): what the readiness check blocks stops here, before anything is written or opened.
        readiness.requireReady(readiness.check(taskId, target.key(), null, false));

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
            for (String governance : List.of("AGENTS.md", "CLAUDE.md", "GEMINI.md")) {
                writeIfOurs(folder, governance, HandoffPackager.inboxGovernance(packagePath), written);
            }
        } else {
            // PHASE 34: a folder the operator brought has no governance until now -- every tool's file,
            // where it is missing, so that Claude Code (CLI or app), Codex, Antigravity... each find theirs.
            workspace.ensureGovernance(project.getId()).stream()
                    .filter(entry -> entry.outcome() == ProjectWorkspaceService.Outcome.CREATED)
                    .forEach(entry -> written.add(entry.path()));
            // PHASE 36: a project begun earlier follows the way tasks are delivered now, where the text is still ours.
            written.addAll(workspace.upgradeGenerated(project.getId()));
        }
        if (target.contextFile() != null && !List.of("AGENTS.md", "CLAUDE.md").contains(target.contextFile())) {
            writeIfOurs(folder, target.contextFile(), HandoffPackager.targetRules(target, packagePath), written);
        }

        HarnessService.AgentHarness own = harness.of(agent.getId());
        List<com.aicompany.backend.harness.model.HarnessResource> equipped = own.resources();
        // PHASE 34: what the project adopted reaches every agent working on it, after the agent's own.
        List<com.aicompany.backend.harness.model.HarnessResource> adopted = adoptedBeyond(project, equipped);
        List<com.aicompany.backend.harness.model.HarnessResource> all = new ArrayList<>(equipped);
        all.addAll(adopted);
        String taskText = task.getTitle() + "\n" + (task.getDescription() == null ? "" : task.getDescription());
        java.util.Map<String, HandoffPackager.LibraryUse> uses = libraryUses(target, all, taskText);
        String mcpNote = configureMcp(target, folder, all, written);
        Agent parent = agent.getParentId() == null ? null : agents.findById(agent.getParentId()).orElse(null);
        // PHASE 36: the agent's model as it stands on the tool the task goes to, not on the agent's own target.
        com.aicompany.backend.hitl.LearningMode mode = learning != null ? learning
                : com.aicompany.backend.hitl.LearningMode.defaultFor(project == null
                ? com.aicompany.backend.project.model.AutonomyLevel.GUIDED : project.getAutonomyLevel());
        HandoffPackager.Package pkg = HandoffPackager.build(task, project, agent, parent, equipped, adopted, own.software(),
                binding.describe(agent.getModel(), target.key(), binding.availability()), target, folder, taskDocument,
                workspace.readIn(folder, taskDocument).orElse(null), packagePath, uses, mcpNote, mode);
        workspace.writeIn(folder, packagePath, pkg.document());
        written.add(packagePath);
        // PHASE 37 (ADR-047): the diary the agent keeps while it works -- created once, never overwritten:
        // a second handoff of the same task goes on writing in it.
        String diary = com.aicompany.backend.plan.service.PlanMarkdown.diaryPath(taskDocument);
        if (workspace.readIn(folder, diary).isEmpty()) {
            workspace.writeIn(folder, diary, com.aicompany.backend.journal.DiaryFormat.template(label));
            written.add(diary);
        }
        // ...and the folder as it is now, before the tool is opened: what the agent changes is measured against it.
        String copy = changes.snapshot(task.getId(), folder);

        if (task.getStatus() == TaskStatus.OPEN) {
            transitions.apply(task, TaskTransition.START, project, agent, requestedBy, TaskTransitionRecord.Via.HANDOFF);
        }
        markPhaseStarted(task.getPhase());

        String command = null;
        String openUrl = null;
        Integer followPort = null;
        // PHASE 36 (ADR-042): with the launcher off the delivery is prepared whole -- package, governance, prompt,
        // the command it would run -- and the tool is not opened; the operator opens it. Before, the handoff
        // failed with 503 after the package was written.
        String notOpened = target.delivery() == ExecutionTargetCatalog.Delivery.MANUAL
                || target.delivery() == ExecutionTargetCatalog.Delivery.WEB_PASTE ? null
                : software.launcherUnavailable().orElse(null);
        boolean open = notOpened == null;
        switch (target.delivery()) {
            case CLI_PROMPT -> {
                Software cli = softwareOf(target);
                // PHASE 32 (TD-49): OpenCode's TUI serves its API on a port chosen here, to follow the session.
                followPort = open && "opencode".equals(cliCommand(cli)) ? OpenCodeSessions.freePort() : null;
                List<String> arguments = cliArguments(cli, pkg.compactPrompt(), followPort, openClawSession(agent, task));
                command = String.join(" ", (open ? software.launchIn(cli.getKey(), folder, arguments)
                        : software.planIn(cli.getKey(), folder, arguments)).command());
            }
            case IDE_FOLDER, APP_PASTE -> command = String.join(" ", (open
                    ? software.launchIn(softwareOf(target).getKey(), folder, List.of())
                    : software.planIn(softwareOf(target).getKey(), folder, List.of())).command());
            // PHASE 34: the app opens on the folder with the prompt in its composer, as a CLI gets it.
            case APP_LINK -> command = String.join(" ", (open
                    ? software.launchLink(softwareOf(target).getKey(), target.link(), folder, pkg.compactPrompt())
                    : software.planLink(softwareOf(target).getKey(), target.link(), folder, pkg.compactPrompt())).command());
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
                command, requestedBy).followedOn(followPort).measured(HandoffPackager.chars(pkg.document())).teaching(mode));
        changes.bind(task.getId(), copy, new com.aicompany.backend.journal.WorkChanges.Baseline(handoff.getId(),
                handoff.getCreatedAt()));
        // PHASE 35 (ADR-041): the size of each file the package asks the agent to read, as it is now.
        reads.record(handoff.getId(), measureReads(HandoffPackager.reads(task, project, taskDocument, all, uses),
                folder, all, uses));
        tasks.saveAndFlush(task);
        // PHASE 36: only a CLI gets the prompt as its argument for sure. Claude Desktop's link fills the composer
        // when the app takes it, and the prompt is in the clipboard as well, for when it does not.
        boolean promptArrives = open && target.delivery() == ExecutionTargetCatalog.Delivery.CLI_PROMPT;
        return new HandoffResult(handoff, task, pkg.compactPrompt(), pkg.fullPrompt(), target.delivery().name(),
                folder.toString(), openUrl, List.copyOf(written), !promptArrives, notOpened);
    }

    /**
     * PHASE 32 (TD-48): the agent's MCP servers, in the project file of the tool that
     * takes the handoff; what the Hub cannot configure is said in the package.
     */
    private String configureMcp(ExecutionTarget target, Path folder,
                                List<com.aicompany.backend.harness.model.HarnessResource> equipped, List<String> written) {
        HandoffMcp.Plan plan = HandoffMcp.plan(target.key(), folder, equipped);
        StringBuilder note = new StringBuilder();
        if (plan.file() != null && !plan.servers().isEmpty()) {
            List<String> added = new ArrayList<>();
            String merged = HandoffMcp.merge(plan.file(), workspace.readIn(folder, plan.file()), plan.servers(), added);
            if (merged != null) {
                workspace.writeToolConfig(folder, plan.file(), merged);
                written.add(plan.file());
            }
            note.append("\n\nServer MCP del progetto, in `").append(plan.file()).append("`: ")
                    .append(String.join(", ", plan.servers().stream().map(HandoffMcp.Server::key).toList()))
                    .append(added.size() < plan.servers().size() ? " (quelli già presenti restano come erano)" : "")
                    .append(". Avvialo dalla cartella del progetto perché li carichi.");
        }
        if (!plan.manual().isEmpty()) {
            note.append("\n\nDa configurare a mano in ").append(target.name()).append(": ")
                    .append(String.join(", ", plan.manual())).append(plan.file() == null
                            ? " (AI Company OS non scrive la configurazione MCP di questo strumento)."
                            : " (la voce non dice come avviarli con un solo comando).");
        }
        return note.toString();
    }

    /**
     * PHASE 35 (ADR-041): each file the package names, with its characters -- a library
     * file read whole from the library, any other from the working folder; null when it
     * is not there. A measure never fails a handoff: a file that cannot be read is null.
     */
    private List<HandoffReads.Row> measureReads(List<HandoffPackager.Read> asked, Path folder,
                                                List<com.aicompany.backend.harness.model.HarnessResource> resources,
                                                java.util.Map<String, HandoffPackager.LibraryUse> uses) {
        java.util.Map<String, com.aicompany.backend.harness.model.HarnessResource> libraryFiles = new java.util.HashMap<>();
        resources.stream().filter(r -> uses.containsKey(r.getKey()))
                .forEach(r -> libraryFiles.put(uses.get(r.getKey()).libraryPath(), r));
        List<HandoffReads.Row> rows = new ArrayList<>();
        for (HandoffPackager.Read read : asked) {
            boolean library = read.kind() == HandoffPackager.ReadKind.SKILL || read.kind() == HandoffPackager.ReadKind.KNOWLEDGE;
            Integer chars;
            try {
                chars = (library ? this.library.fileText(libraryFiles.get(read.path())) : workspace.readIn(folder, read.path()))
                        .map(HandoffPackager::chars).orElse(null);
            } catch (RuntimeException unreadable) {
                chars = null;
            }
            rows.add(new HandoffReads.Row(read.kind().name(), read.path(), read.required(), chars));
        }
        return rows;
    }

    /**
     * How the package gives each library entry that has a file. A tool that loads skills
     * by itself (ADR-041 §8) has each skill checked through the Knowledge Hub -- where its
     * copy is, and how it stands -- and the knowledge given by path; any other tool reads
     * every file, as before.
     */
    private java.util.Map<String, HandoffPackager.LibraryUse> libraryUses(
            ExecutionTarget target, List<com.aicompany.backend.harness.model.HarnessResource> resources, String taskText) {
        // PHASE 35 (ADR-041 §8): a tool that loads skills by itself is asked to read only the pertinent knowledge;
        // PHASE 36: the rest is named, to read if the task needs it -- never dropped.
        boolean loadsSkills = hub.loadsSkills(target.key());
        java.util.Map<String, HandoffPackager.LibraryUse> uses = new java.util.LinkedHashMap<>();
        for (var r : resources) {
            Optional<SkillLibrary.FileCheck> check = library.check(r);
            if (check.isEmpty()) {
                continue;
            }
            boolean skill = r.getKind() == com.aicompany.backend.harness.model.HarnessResource.Kind.SKILL;
            SkillHubService.ToolCopy copy = skill && check.get().status() == SkillLibrary.FileCheck.Status.OK
                    ? hub.copyForTarget(r.getKey(), target.key()).orElse(null) : null;
            boolean pertinent = skill || !loadsSkills || Pertinence.score(taskText, r) >= Pertinence.THRESHOLD;
            uses.put(r.getKey(), new HandoffPackager.LibraryUse(check.get().path(), copy, pertinent, check.get().status(),
                    check.get().detail()));
        }
        return uses;
    }

    /** PHASE 34: the project's adopted resources the agent does not already carry, in the project's order. */
    private List<com.aicompany.backend.harness.model.HarnessResource> adoptedBeyond(
            Project project, List<com.aicompany.backend.harness.model.HarnessResource> equipped) {
        if (project == null) {
            return List.of();
        }
        java.util.Set<String> carried = new java.util.HashSet<>();
        equipped.forEach(r -> carried.add(r.getKey()));
        return harness.ofProject(project.getId()).stream().filter(r -> !carried.contains(r.getKey())).toList();
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
            transitions.apply(task, TaskTransition.COMPLETE, project, null, reviewer, TaskTransitionRecord.Via.REVIEW);
            markPhaseDoneIfComplete(task);
        } else if (task.getStatus() == TaskStatus.DONE) {
            transitions.apply(task, TaskTransition.REOPEN, project, null, reviewer, TaskTransitionRecord.Via.REVIEW);
        }
        TaskReview review = reviews.save(new TaskReview(taskId, verdict, note, runId, handoffId, reviewer,
                project == null ? null : project.getAutonomyLevel()));
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
    static List<String> cliArguments(Software target, String prompt, @org.jspecify.annotations.Nullable Integer followPort,
                                     String openClawSession) {
        return switch (cliCommand(target)) {
            case "opencode" -> followPort == null ? List.of("--prompt", prompt)
                    : List.of("--port", String.valueOf(followPort), "--prompt", prompt);
            // PHASE 37 (ADR-043): `openclaw tui` on a session of the task's own, with the prompt sent on connecting.
            case "openclaw" -> List.of("--session", openClawSession, "--message", prompt);
            default -> List.of(prompt);
        };
    }

    /**
     * PHASE 37 (ADR-043): the OpenClaw session a task is worked in -- one per task, so that
     * handing it off again continues the same conversation. {@code agent:<id>:<key>} when
     * the agent stands in OpenClaw (published there, or brought in from there); a bare key,
     * which OpenClaw gives to its default agent, when it does not.
     */
    private String openClawSession(Agent agent, Task task) {
        String key = "aicos-task-" + task.getId();
        return publications.find(agent.getId(), ExecutionTargetCatalog.OPENCLAW)
                .map(p -> "agent:" + p.externalId() + ":" + key).orElse(key);
    }

    private static String cliCommand(Software target) {
        return target.getCliCommand() == null ? "" : target.getCliCommand().strip().split("\\s+")[0];
    }

    /** PHASE 32 (TD-49): what the OpenCode session a handoff opened is doing; read-only. */
    @Transactional(readOnly = true)
    public OpenCodeSessions.OpenCodeSession followHandoff(Long handoffId) {
        TaskHandoff handoff = handoffs.findById(handoffId)
                .orElseThrow(() -> new HandoffNotFoundException(handoffId));
        return sessions.follow(handoff.getFollowPort());
    }

    private void markPhaseStarted(ProjectPhase phase) {
        if (phase != null && lockedForProgress(phase).getStatus() == ProjectPhase.Status.PLANNED) {
            phase.moveTo(ProjectPhase.Status.IN_PROGRESS);
        }
    }

    /**
     * PHASE 32 (TD-41): the phase is read again under an exclusive lock before its
     * progress is written. It was loaded unlocked with the task, so an approval that
     * committed meanwhile left this copy one version behind, and the flush failed
     * with an optimistic-lock conflict -- a 500. Re-read under the lock it waits for
     * that approval, sees its version, and writes on top of it (ADR-009 §2.2).
     */
    private ProjectPhase lockedForProgress(ProjectPhase phase) {
        entityManager.refresh(phase, LockModeType.PESSIMISTIC_WRITE);
        return phase;
    }

    private void markPhaseDoneIfComplete(Task completed) {
        ProjectPhase phase = completed.getPhase();
        if (phase == null) {
            return;
        }
        lockedForProgress(phase);
        boolean allDone = tasks.findAllByProjectId(completed.getProjectId()).stream()
                .filter(t -> phase.getId().equals(t.getPhaseId()))
                .allMatch(t -> t.getId().equals(completed.getId()) || t.getStatus() == TaskStatus.DONE);
        phase.moveTo(allDone ? ProjectPhase.Status.DONE : ProjectPhase.Status.IN_PROGRESS);
    }
}
