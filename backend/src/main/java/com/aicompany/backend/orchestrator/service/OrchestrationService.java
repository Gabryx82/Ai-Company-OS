package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.agent.routing.AgentRouter;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.ExecutionTargetCatalog;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelRole;
import com.aicompany.backend.llm.service.LlmCatalogService;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.service.PlanDocument;
import com.aicompany.backend.plan.service.PlanMarkdown;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.service.ProjectTypeCatalog;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The execution half of the Master Orchestrator's thinking (ADR-021 §3): for one
 * task, which agent, which model, which software, which files, which prompt and
 * which targets -- each with the reason it was chosen. Read-only: deciding is
 * not doing, and the operator sees the decision before anything happens.
 */
@Service
@Transactional(readOnly = true)
public class OrchestrationService {

    public record AgentChoice(Long agentId, String name, String role, String model, boolean assigned, String reason) {
    }

    public record ModelChoice(String model, String role, Boolean available, String reason) {
    }

    public record SoftwareChoice(String key, String name, Availability availability, boolean launchable,
                                 boolean executionTarget, String reason) {
    }

    public record ContextFile(String path, boolean exists, String why) {
    }

    public enum TargetKind { ENGINE, CLI, DESKTOP, WEB, MANUAL }

    /**
     * One place the task can be done (ADR-025 §2). {@code recommended} marks the
     * execution target the agent is bound to; {@code delivery} says how the
     * prompt gets there.
     */
    public record Target(String key, String name, TargetKind kind, boolean available, String detail, String delivery,
                         boolean recommended) {
    }

    public record Orchestration(Long taskId, String code, String title, TaskStatus status, Long projectId,
                                String projectName, Long phaseId, Integer phaseNumber, String phaseTitle,
                                boolean phaseApproved, AutonomyLevel autonomyLevel, String autonomyRules,
                                AgentChoice agent, ModelChoice model, List<SoftwareChoice> software,
                                List<ContextFile> context, String prompt, List<Target> targets,
                                List<String> blockers, AgentBindingService.Binding binding) {
    }

    private static final Pattern CODE_WORK = Pattern.compile(
            // A word start, then the stem: "implementare" matches, "guida" does not match "ui".
            "(?<!\\p{L})(implement|codice|code|api|endpoint|test|database|db|schema|backend|frontend|crud|classe"
                    + "|class|refactor|servlet|componente|component|migrazion|migration|bug|fix|build|script)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VISUAL_WORK = Pattern.compile(
            "(?<!\\p{L})(ui|ux|grafic|design|mockup|layout|3d|immagin|image|interfaccia|interface|stile|style"
                    + "|reference)",
            Pattern.CASE_INSENSITIVE);

    private final TaskRepository tasks;
    private final AgentRouter router;
    private final LlmCatalogService models;
    private final SoftwareService software;
    private final ProjectTypeCatalog types;
    private final ProjectWorkspaceService workspace;
    private final ExecutionTargetCatalog targetCatalog;
    private final AgentBindingService bindings;
    private final AgentRepository agents;

    public OrchestrationService(TaskRepository tasks, AgentRouter router, LlmCatalogService models,
                                SoftwareService software, ProjectTypeCatalog types, ProjectWorkspaceService workspace,
                                ExecutionTargetCatalog targetCatalog, AgentBindingService bindings,
                                AgentRepository agents) {
        this.targetCatalog = targetCatalog;
        this.bindings = bindings;
        this.agents = agents;
        this.tasks = tasks;
        this.router = router;
        this.models = models;
        this.software = software;
        this.types = types;
        this.workspace = workspace;
    }

    public Orchestration decide(Long taskId) {
        Task task = tasks.findByIdWithAssociations(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        Project project = task.getProject();
        ProjectPhase phase = task.getPhase();
        String work = task.getTitle() + " " + (task.getDescription() == null ? "" : task.getDescription());
        Optional<PlanDocument.TaskPlan> planned = plannedTask(task);

        List<String> blockers = new ArrayList<>();
        if (task.getStatus() == TaskStatus.DONE) {
            blockers.add("La task è già completata: riaprila per lavorarci ancora.");
        }
        if (phase != null && !phase.isApproved()) {
            blockers.add("La fase " + phase.getNumber() + " non è approvata (Human-in-the-Loop).");
        }
        AgentChoice agent = agent(task, work, planned);
        if (!agent.assigned()) {
            blockers.add("Nessun agente assegnato: assegna quello proposto o un altro.");
        }
        boolean hasFolder = project != null && workspace.folderOf(project.getId()).isPresent();
        // PHASE 17 (ADR-025 §4): a handoff prepares the folder itself -- the project's (created when
        // missing, once its path is configured) or an inbox folder for a task without a project.
        boolean folderPossible = project == null || project.getWorkspacePath() != null;
        if (!folderPossible) {
            blockers.add("Il progetto non ha una cartella di lavoro: impostala nel profilo per gli handoff.");
        }

        Agent bound = agent.agentId() == null ? null : agents.findById(agent.agentId()).orElse(null);
        AgentBindingService.Binding binding = bound == null ? null : bindings.describe(bound);
        List<SoftwareService.Detected> catalog = software.findAll();
        List<SoftwareChoice> tools = software(task, project, work, planned, catalog);
        List<Target> targets = targets(agent, bound, binding, catalog, folderPossible);
        AutonomyLevel level = project == null ? AutonomyLevel.GUIDED : project.getAutonomyLevel();

        return new Orchestration(task.getId(), task.getCode(), task.getTitle(), task.getStatus(),
                project == null ? null : project.getId(), project == null ? null : project.getName(),
                phase == null ? null : phase.getId(), phase == null ? null : phase.getNumber(),
                phase == null ? null : phase.getTitle(), phase == null || phase.isApproved(), level,
                AutonomyPolicy.rules(level), agent, model(agent, bound, work), tools,
                context(task, project, phase, work, planned, hasFolder),
                task.getCode() == null
                        ? "Esegui TASK-" + task.getId() + " seguendo il pacchetto di handoff in `.aicos/handoffs/` e la governance in `AGENTS.md`."
                        : PlanMarkdown.compactPrompt(task.getCode()),
                targets, blockers, binding);
    }

    // --- agent ---------------------------------------------------------------------

    private AgentChoice agent(Task task, String work, Optional<PlanDocument.TaskPlan> planned) {
        Agent assigned = task.getAgent();
        if (assigned != null) {
            return new AgentChoice(assigned.getId(), assigned.getName(), assigned.getRole(), assigned.getModel(), true,
                    "Assegnato alla task" + (assigned.isActive() ? "" : " (ma inattivo: la task non può partire)"));
        }
        String role = planned.map(PlanDocument.TaskPlan::agentRole).orElse("");
        return router.suggest(role + " " + work).stream()
                .filter(suggestion -> suggestion.score() > 0)
                .findFirst()
                .map(s -> new AgentChoice(s.agentId(), s.name(), s.role(), s.model(), false,
                        "Proposto dal routing: " + String.join(", ", s.matchedTerms())))
                .orElse(new AgentChoice(null, null, null, null, false, "Nessun agente corrisponde alla task"));
    }

    // --- model ---------------------------------------------------------------------

    private ModelChoice model(AgentChoice agent, Agent bound, String work) {
        if (agent.model() != null) {
            Optional<Boolean> available = models.catalog().models().stream()
                    .filter(view -> view.model().getKey().equals(agent.model()))
                    .map(LlmCatalogService.ModelView::engineAvailable)
                    .findFirst();
            String where = bound == null || ExecutionTargetCatalog.ENGINE.equals(bound.getExecutionTarget()) ? ""
                    : targetCatalog.find(bound.getExecutionTarget()).map(t -> ", usato tramite " + t.name()).orElse("");
            return new ModelChoice(agent.model(), null, available.orElse(null), "Il modello dell'agente" + where);
        }
        if (bound != null && !ExecutionTargetCatalog.ENGINE.equals(bound.getExecutionTarget())) {
            return new ModelChoice(null, null, null, "Il modello lo sceglie "
                    + targetCatalog.find(bound.getExecutionTarget()).map(ExecutionTarget::name).orElse("lo strumento"));
        }
        ModelRole wanted = CODE_WORK.matcher(work).find() ? ModelRole.CODER : ModelRole.GENERAL;
        LlmCatalogService.Catalog catalog = models.catalog();
        for (ModelRole role : List.of(wanted, ModelRole.PLANNER, ModelRole.GENERAL, ModelRole.FAST)) {
            Optional<LlmCatalogService.ModelView> found = catalog.models().stream()
                    .filter(view -> view.model().getRole() == role)
                    .filter(view -> view.model().getLifecycle() == ModelLifecycle.ACTIVE)
                    .filter(view -> Boolean.TRUE.equals(view.engineAvailable()))
                    .findFirst();
            if (found.isPresent()) {
                return new ModelChoice(found.get().model().getKey(), role.name(), true,
                        role == wanted ? "Modello attivo del ruolo " + role + " richiesto dalla task"
                                : "Nessun modello " + wanted + " pronto: ripiego su " + role);
            }
        }
        return new ModelChoice(catalog.engineDefault(), null, catalog.engineReachable() ? true : null,
                "Nessun modello di catalogo pronto: il default dell'AI Engine");
    }

    // --- software ------------------------------------------------------------------

    private List<SoftwareChoice> software(Task task, Project project, String work,
                                          Optional<PlanDocument.TaskPlan> planned,
                                          List<SoftwareService.Detected> catalog) {
        Map<String, String> reasons = new LinkedHashMap<>();
        planned.ifPresent(p -> p.software().forEach(key -> reasons.putIfAbsent(key, "Indicato dal piano")));
        if (project != null && project.getProjectType() != null) {
            types.of(project.getProjectType()).ifPresent(info -> info.software()
                    .forEach(key -> reasons.putIfAbsent(key, "Consigliato per " + info.label())));
        }
        Set<String> terms = new java.util.HashSet<>(List.of(work.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#.-]+")));
        for (SoftwareService.Detected d : catalog) {
            for (String capability : d.software().getCapabilities()) {
                if (terms.contains(capability)) {
                    reasons.putIfAbsent(d.software().getKey(), "Capability «" + capability + "» richiesta dalla task");
                }
            }
        }
        List<SoftwareChoice> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : reasons.entrySet()) {
            catalog.stream().filter(d -> d.software().getKey().equals(entry.getKey()) && d.software().isEnabled())
                    .findFirst()
                    .ifPresent(d -> out.add(new SoftwareChoice(d.software().getKey(), d.software().getName(),
                            d.detection().availability(), launchable(d), d.software().isExecutionTarget(),
                            entry.getValue())));
            if (out.size() == 8) {
                break;
            }
        }
        return out;
    }

    private static boolean launchable(SoftwareService.Detected d) {
        Availability a = d.detection().availability();
        return d.software().getLaunchKind() == LaunchKind.WEB || a == Availability.INSTALLED
                || a == Availability.RUNNING || a == Availability.STOPPED;
    }

    // --- context -------------------------------------------------------------------

    private List<ContextFile> context(Task task, Project project, ProjectPhase phase, String work,
                                      Optional<PlanDocument.TaskPlan> planned, boolean hasFolder) {
        List<ContextFile> files = new ArrayList<>();
        if (project == null || task.getDocumentPath() == null) {
            return files;
        }
        Path folder = hasFolder ? workspace.folderOf(project.getId()).orElse(null) : null;
        add(files, folder, "AGENTS.md", "Governance del progetto");
        add(files, folder, task.getDocumentPath(), "Il documento della task");
        if (phase != null) {
            add(files, folder, phase.getDocumentPath(), "La fase: scope, strategia, criteri");
        }
        add(files, folder, "docs/IMPLEMENTATION_PLAN.md", "Il piano complessivo");
        add(files, folder, "MASTER_PROMPT.md", "Gli obiettivi del progetto");
        planned.ifPresent(p -> p.files().stream().limit(10).forEach(file -> add(files, folder,
                file.replace("`", ""), "Indicato dalla task")));
        if (VISUAL_WORK.matcher(work).find()) {
            add(files, folder, "references/README.md", "Reference visuali: la task riguarda UI o grafica");
        }
        return files;
    }

    private static void add(List<ContextFile> files, Path folder, String relative, String why) {
        if (files.stream().anyMatch(f -> f.path().equals(relative))) {
            return;
        }
        boolean exists = false;
        if (folder != null) {
            try {
                exists = Files.exists(WorkspacePaths.inside(folder, relative));
            } catch (RuntimeException refused) {
                return; // a path the plan named that leaves the folder is not context
            }
        }
        files.add(new ContextFile(relative, exists, why));
    }

    // --- targets -------------------------------------------------------------------

    private List<Target> targets(AgentChoice agent, Agent bound, AgentBindingService.Binding binding,
                                 List<SoftwareService.Detected> catalog, boolean folderPossible) {
        Map<String, SoftwareService.Detected> byKey = new LinkedHashMap<>();
        catalog.forEach(d -> byKey.put(d.software().getKey(), d));
        String boundTarget = bound == null ? null : bound.getExecutionTarget();
        List<Target> out = new ArrayList<>();
        for (ExecutionTarget t : targetCatalog.all()) {
            boolean recommended = t.key().equals(boundTarget);
            TargetKind kind = switch (t.delivery()) {
                case ENGINE_RUN -> TargetKind.ENGINE;
                case CLI_PROMPT -> TargetKind.CLI;
                case WEB_PASTE -> TargetKind.WEB;
                case MANUAL -> TargetKind.MANUAL;
                default -> TargetKind.DESKTOP;
            };
            boolean available;
            String detail;
            if (t.isEngine()) {
                boolean runnable = binding == null || binding.model() == null || binding.model().engineRunnable();
                available = agent.assigned() && runnable && (bound == null || ExecutionTargetCatalog.ENGINE.equals(boundTarget));
                detail = !agent.assigned() ? "Serve un agente assegnato"
                        : !runnable ? "Il modello dell'agente si usa tramite la sua app, non nell'AI Engine"
                        : !available ? "L'agente lavora con un'app o una CLI: usa l'handoff" : t.howItWorks();
            } else if (t.delivery() == ExecutionTargetCatalog.Delivery.MANUAL) {
                available = agent.assigned() && folderPossible;
                detail = t.howItWorks();
            } else {
                SoftwareService.Detected d = t.software() == null ? null : byKey.get(t.software());
                boolean installed = t.delivery() == ExecutionTargetCatalog.Delivery.WEB_PASTE
                        || (d != null && d.software().isEnabled()
                        && (d.detection().availability() == Availability.INSTALLED
                        || d.detection().availability() == Availability.RUNNING));
                available = installed && agent.assigned() && folderPossible;
                detail = !installed ? "Non installato su questa macchina"
                        : !agent.assigned() ? "Serve un agente assegnato"
                        : !folderPossible ? "Serve la cartella di lavoro del progetto" : t.howItWorks();
            }
            out.add(new Target(t.key(), t.name(), kind, available,
                    recommended ? "Consigliato: l'agente è configurato per lavorare qui. " + detail : detail,
                    t.delivery().name(), recommended));
        }
        return out;
    }

    // --- the plan entry of this task -----------------------------------------------

    /** The task's own entry in plan.json: TASK-NNN is the n-th task in plan order (ADR-021 §2). */
    private Optional<PlanDocument.TaskPlan> plannedTask(Task task) {
        if (task.getCode() == null || task.getProject() == null) {
            return Optional.empty();
        }
        try {
            Optional<String> text = workspace.readText(task.getProject().getId(), ".aicos/plan.json");
            if (text.isEmpty()) {
                return Optional.empty();
            }
            PlanDocument plan = PlanDocument.parse(text.get());
            int index = Integer.parseInt(task.getCode().substring("TASK-".length())) - 1;
            return plan.phases().stream().flatMap(p -> p.tasks().stream()).skip(index).findFirst();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
