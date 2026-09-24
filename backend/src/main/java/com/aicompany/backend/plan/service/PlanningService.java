package com.aicompany.backend.plan.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentStatus;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.ProblemException;
import com.aicompany.backend.plan.exception.MasterPromptMissingException;
import com.aicompany.backend.plan.exception.PhaseNotFoundException;
import com.aicompany.backend.plan.exception.PlanLockedException;
import com.aicompany.backend.plan.exception.PlanRunNotFoundException;
import com.aicompany.backend.plan.exception.PlanningInProgressException;
import com.aicompany.backend.plan.model.PlanRun;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.repository.PlanRunRepository;
import com.aicompany.backend.plan.repository.ProjectPhaseRepository;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.PlanStatus;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.exception.WorkspaceFileNotFoundException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The planning half of the Master Orchestrator (ADR-021): from
 * {@code MASTER_PROMPT.md} to an implementation plan, through the AI Engine or
 * through an external agent, and the operator's approvals of what came out.
 *
 * <p>A generation is a background job with its own record, like a task run
 * (ADR-016): created in one transaction, executed after it committed, finished
 * in another, and always finished with a type.
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    static final int PLAN_MAX_TOKENS = 4_000;

    public record PhaseView(ProjectPhase phase, List<Task> tasks) {
    }

    public record PlanView(Project project, List<PhaseView> phases, List<PlanRun> runs) {
    }

    private final ProjectRepository projects;
    private final ProjectPhaseRepository phases;
    private final PlanRunRepository planRuns;
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final SoftwareRepository software;
    private final ProjectWorkspaceService workspace;
    private final PlanImporter importer;
    private final EngineClient engine;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    public PlanningService(ProjectRepository projects, ProjectPhaseRepository phases, PlanRunRepository planRuns,
                           TaskRepository tasks, AgentRepository agents, SoftwareRepository software,
                           ProjectWorkspaceService workspace, PlanImporter importer, EngineClient engine,
                           @Qualifier("planExecutor") TaskExecutor planExecutor, TransactionTemplate transactions) {
        this.projects = projects;
        this.phases = phases;
        this.planRuns = planRuns;
        this.tasks = tasks;
        this.agents = agents;
        this.software = software;
        this.workspace = workspace;
        this.importer = importer;
        this.engine = engine;
        this.executor = planExecutor;
        this.transactions = transactions;
    }

    // --- reading ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PlanView view(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        List<Task> all = tasks.findAllByProjectId(projectId);
        List<PhaseView> views = phases.findByProject(projectId).stream()
                .map(phase -> new PhaseView(phase, all.stream()
                        .filter(task -> phase.getId().equals(task.getPhaseId()))
                        .toList()))
                .toList();
        return new PlanView(project, views, planRuns.findTop10ByProjectIdOrderByIdDesc(projectId));
    }

    @Transactional(readOnly = true)
    public PlanRun run(Long runId) {
        return planRuns.findById(runId).orElseThrow(() -> new PlanRunNotFoundException(runId));
    }

    /** The planning request as an external agent reads it. */
    @Transactional(readOnly = true)
    public String handoffText(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return PlanPrompt.handoff(project, agentRoles(), softwareKeys());
    }

    // --- generating ---------------------------------------------------------------

    /** Starts a generation through the AI Engine; the run is returned while it works. */
    public PlanRun generate(Long projectId, String model, String requestedBy) {
        String masterPrompt = requireMasterPrompt(projectId);
        PlanRun run;
        try {
            run = transactions.execute(status -> {
                Project project = projects.findByIdForUpdate(projectId)
                        .orElseThrow(() -> new ProjectNotFoundException(projectId));
                requireReplaceable(project);
                if (planRuns.findFirstByProjectIdAndStatus(projectId, PlanRun.Status.RUNNING).isPresent()) {
                    throw new PlanningInProgressException("A plan is already being generated for project " + projectId);
                }
                return planRuns.saveAndFlush(new PlanRun(projectId, PlanRun.Source.ENGINE, model, requestedBy));
            });
        } catch (DataIntegrityViolationException raced) {
            throw new PlanningInProgressException("A plan is already being generated for project " + projectId);
        }
        Long runId = run.getId();
        executor.execute(() -> execute(runId, projectId, model, masterPrompt));
        return run;
    }

    /**
     * The generation, in stages (ADR-021 §2): the phases, then the tasks of each
     * phase, every call constrained by its JSON Schema. The run records which
     * stage it is in, every raw answer (for diagnosis when a stage fails), and
     * the tokens of all stages together.
     */
    void execute(Long runId, Long projectId, String model, String masterPrompt) {
        StringBuilder output = new StringBuilder();
        String served = null;
        int tokensIn = 0;
        int tokensOut = 0;
        try {
            Project project = transactions.execute(status -> projects.findById(projectId).orElseThrow());
            progress(runId, "Fase del piano: elenco delle fasi");
            EngineClient.Completion skeletonAnswer = engine.complete(new EngineClient.Request(model,
                    PlanPrompt.phasesSystem(), PlanPrompt.user(project, masterPrompt), PLAN_MAX_TOKENS,
                    correlation(runId, 0), Map.of("purpose", "plan-phases", "project", String.valueOf(projectId)),
                    "json", PlanPrompt.PHASES_SCHEMA));
            output.append(skeletonAnswer.output());
            served = skeletonAnswer.model();
            tokensIn += skeletonAnswer.inputTokens();
            tokensOut += skeletonAnswer.outputTokens();
            PlanDocument skeleton = PlanDocument.parseSkeleton(skeletonAnswer.output());

            List<String> titles = skeleton.phases().stream().map(PlanDocument.PhasePlan::title).toList();
            List<String> roles = agentRoles();
            List<String> keys = softwareKeys();
            List<List<PlanDocument.TaskPlan>> tasksByPhase = new java.util.ArrayList<>();
            for (int i = 0; i < skeleton.phases().size(); i++) {
                progress(runId, "Task della fase " + (i + 1) + "/" + titles.size() + ": " + titles.get(i));
                EngineClient.Completion tasksAnswer = engine.complete(new EngineClient.Request(model,
                        PlanPrompt.tasksSystem(roles, keys),
                        PlanPrompt.tasksUser(project, masterPrompt, skeleton.summary(), titles, i, skeleton.phases().get(i)),
                        PLAN_MAX_TOKENS, correlation(runId, i + 1),
                        Map.of("purpose", "plan-tasks", "project", String.valueOf(projectId)),
                        "json", PlanPrompt.TASKS_SCHEMA));
                output.append("\n\n").append(tasksAnswer.output());
                tokensIn += tasksAnswer.inputTokens();
                tokensOut += tasksAnswer.outputTokens();
                tasksByPhase.add(PlanDocument.parseTasks(tasksAnswer.output()));
            }

            progress(runId, "Scrittura di piano, fasi e task");
            PlanDocument plan = skeleton.withTasks(tasksByPhase);
            PlanImporter.Imported imported = importer.importPlan(projectId, plan, "AI Engine, " + served);
            String finalServed = served;
            String finalOutput = output.toString();
            int finalIn = tokensIn;
            int finalOut = tokensOut;
            transactions.executeWithoutResult(status -> {
                PlanRun run = planRuns.findById(runId).orElseThrow();
                run.progress(null);
                run.succeed(imported.phases(), imported.tasks(), finalOutput, finalServed, finalIn, finalOut);
            });
        } catch (EngineFailure failure) {
            finish(runId, failure.type() + ": " + failure.detail(), output.toString(), served);
        } catch (ProblemException refused) {
            finish(runId, refused.problem().slug() + ": " + refused.getMessage()
                    + (refused.errors().isEmpty() ? "" : " " + refused.errors()), output.toString(), served);
        } catch (RuntimeException unexpected) {
            log.error("Plan run {} failed unexpectedly", runId, unexpected);
            finish(runId, "internal: " + unexpected.getClass().getSimpleName(), output.toString(), served);
        }
    }

    private void progress(Long runId, String text) {
        transactions.executeWithoutResult(status -> planRuns.findById(runId).orElseThrow().progress(text));
    }

    private static String correlation(Long runId, int stage) {
        return "plan-" + runId + "-" + stage + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void finish(Long runId, String detail, String output, String served) {
        transactions.executeWithoutResult(status -> planRuns.findById(runId).orElseThrow()
                .fail(detail, output == null || output.isEmpty() ? null : output, served));
    }

    /** A plan run the process never finished (a restart mid-generation) ends as a failure with a reason. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        transactions.executeWithoutResult(status -> planRuns.findByStatus(PlanRun.Status.RUNNING)
                .forEach(run -> run.fail("interrupted: the control plane stopped during the generation", null, null)));
    }

    // --- importing ---------------------------------------------------------------

    /** Imports {@code .aicos/plan.json}, written by an external agent following the planning handoff. */
    public PlanRun importFromWorkspace(Long projectId, String requestedBy) {
        String text = workspace.readText(projectId, ".aicos/plan.json")
                .orElseThrow(() -> new WorkspaceFileNotFoundException(
                        "No .aicos/plan.json in the project folder: the planner has not written it yet"));
        PlanDocument plan = PlanDocument.parse(text);
        PlanImporter.Imported imported = importer.importPlan(projectId, plan, "import di .aicos/plan.json");
        return transactions.execute(status -> {
            PlanRun run = new PlanRun(projectId, PlanRun.Source.IMPORT, null, requestedBy);
            run.succeed(imported.phases(), imported.tasks(), null, null, null, null);
            return planRuns.save(run);
        });
    }

    // --- approving (ADR-022) -------------------------------------------------------

    @Transactional
    public Project approvePlan(Long projectId, boolean approveAllPhases, Precondition precondition) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        precondition.requireSatisfiedBy(project.getVersion());
        if (project.getPlanStatus() == PlanStatus.NONE) {
            throw new PlanLockedException("Project " + projectId + " has no plan to approve");
        }
        project.markPlan(PlanStatus.APPROVED);
        if (approveAllPhases) {
            phases.findByProject(projectId).stream().filter(phase -> !phase.isApproved())
                    .forEach(phase -> phase.approve("Approvata con il piano"));
        }
        projects.flush();
        return project;
    }

    @Transactional
    public ProjectPhase reviewPhase(Long phaseId, boolean approve, String note, Precondition precondition) {
        ProjectPhase phase = phases.findByIdForUpdate(phaseId)
                .orElseThrow(() -> new PhaseNotFoundException("No phase with id " + phaseId));
        precondition.requireSatisfiedBy(phase.getVersion());
        if (phase.getProject().isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        if (approve) {
            phase.approve(note);
        } else {
            phase.requestChanges(note);
        }
        phases.flush();
        return phase;
    }

    // --- helpers -----------------------------------------------------------------

    private String requireMasterPrompt(Long projectId) {
        String text = workspace.readText(projectId, "MASTER_PROMPT.md")
                .orElseThrow(() -> new MasterPromptMissingException("MASTER_PROMPT.md does not exist yet"));
        if (text.isBlank() || text.contains("Sostituisci questo file con il MASTER PROMPT")) {
            throw new MasterPromptMissingException("MASTER_PROMPT.md is still the template: paste the master prompt first");
        }
        return text;
    }

    private static void requireReplaceable(Project project) {
        if (project.isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        if (project.getPlanStatus() == PlanStatus.APPROVED) {
            throw new PlanLockedException("The plan of project " + project.getId() + " is approved");
        }
    }

    private List<String> agentRoles() {
        return transactions.execute(status -> agents.findAllByStatusOrderByIdAsc(AgentStatus.ACTIVE).stream()
                .map(Agent::getRole).distinct().toList());
    }

    private List<String> softwareKeys() {
        return transactions.execute(status -> software.findAllByOrderByCategoryAscNameAsc().stream()
                .filter(Software::isEnabled).map(Software::getKey).toList());
    }
}
