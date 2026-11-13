package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.BindingProblemException;
import com.aicompany.backend.binding.ExecutionTargetCatalog;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.cost.CostService;
import com.aicompany.backend.harness.hub.SkillHubService;
import com.aicompany.backend.harness.library.Pertinence;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.service.GeneratedTextUpgrades;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PHASE 36 (ADR-042): before a handoff or a run, what the agent will find -- for this
 * agent and this target. Each item is {@code OK}, {@code WARNING} (it arrives, but not
 * whole: the operator should know before opening the tool) or {@code BLOCKING} (the
 * delivery cannot do what it promises). Reads only: nothing is written, nothing opened.
 *
 * <p>The handoff and the run call it too, and refuse on what it blocks; the refusals of
 * the task itself (frozen, done, phase not approved, no active agent) keep their own
 * problems and are listed here with area {@code TASK}.
 */
@Service
@Transactional(readOnly = true)
public class ReadinessService {

    public enum ReadinessStatus { OK, WARNING, BLOCKING }

    /** What the console can do about an item without leaving the panel. */
    public record ReadinessAction(String type, String key, String app) {
    }

    /**
     * @param area {@code TASK}, {@code BINDING}, {@code MODEL}, {@code CONTEXT}, {@code SKILL}, {@code KNOWLEDGE},
     *             {@code MCP}, {@code TOOL}, {@code SOFTWARE}
     */
    public record ReadinessItem(String area, String key, String name, ReadinessStatus status, String detail, @Nullable ReadinessAction action) {
    }

    public record Readiness(Long taskId, String target, String targetName, String delivery, @Nullable Long agentId,
                            @Nullable String agentName, boolean ready, List<ReadinessItem> items) {

        /** What stops the delivery, the task's own refusals included. */
        public List<ReadinessItem> blockers() {
            return items.stream().filter(i -> i.status() == ReadinessStatus.BLOCKING).toList();
        }
    }

    private final TaskRepository tasks;
    private final OrchestrationService orchestration;
    private final ExecutionTargetCatalog targets;
    private final HarnessService harness;
    private final SkillLibrary library;
    private final SkillHubService hub;
    private final SoftwareService software;
    private final AgentBindingService binding;
    private final ProjectWorkspaceService workspace;
    private final EngineClient engine;
    private final CostService costs;
    private final com.aicompany.backend.sync.AgentPublications publications;

    public ReadinessService(TaskRepository tasks, OrchestrationService orchestration, ExecutionTargetCatalog targets,
                            HarnessService harness, SkillLibrary library, SkillHubService hub, SoftwareService software,
                            AgentBindingService binding, ProjectWorkspaceService workspace, EngineClient engine,
                            CostService costs, com.aicompany.backend.sync.AgentPublications publications) {
        this.publications = publications;
        this.tasks = tasks;
        this.orchestration = orchestration;
        this.targets = targets;
        this.harness = harness;
        this.library = library;
        this.hub = hub;
        this.software = software;
        this.binding = binding;
        this.workspace = workspace;
        this.engine = engine;
        this.costs = costs;
    }

    /**
     * @param targetKey the target to check; null for the agent's own execution target
     * @param model     for a run: the model asked for, or null for the agent's
     */
    public Readiness check(Long taskId, @Nullable String targetKey, @Nullable String model) {
        return check(taskId, targetKey, model, true);
    }

    /**
     * @param probeEngine whether to ask the AI Engine if it serves the run's model now -- a warning
     *                    at most. The handoff and the run pass false: inside their transaction a
     *                    call to the engine would hold the task's lock, and it blocks nothing.
     */
    public Readiness check(Long taskId, @Nullable String targetKey, @Nullable String model, boolean probeEngine) {
        Task task = tasks.findByIdWithAssociations(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        Project project = task.getProject();
        Agent agent = task.getAgent();
        String wanted = targetKey == null || targetKey.isBlank()
                ? (agent == null ? ExecutionTargetCatalog.ENGINE : agent.getExecutionTarget()) : targetKey;
        ExecutionTarget target = targets.find(wanted).orElseThrow(() -> BindingProblemException.targetNotFound(wanted));

        List<ReadinessItem> items = new ArrayList<>();
        OrchestrationService.Basics decision = orchestration.basics(taskId);
        decision.blockers().stream()
                // A run needs no folder: only a handoff stops on it.
                .filter(b -> !(target.isEngine() && b.equals(OrchestrationService.NO_FOLDER)))
                .forEach(b -> items.add(new ReadinessItem("TASK", "task", "Task", ReadinessStatus.BLOCKING, b, null)));
        if (agent != null && !agent.isActive()) {
            items.add(new ReadinessItem("TASK", "agent", agent.getName(), ReadinessStatus.BLOCKING,
                    "L'agente è inattivo: riattivalo o assegna la task a un altro.", null));
        }
        if (agent == null) {
            return done(task, target, null, items);
        }

        bindingItems(agent, target, model, probeEngine, items);
        Path folder = project == null ? null : workspace.folderOf(project.getId()).orElse(null);
        contextItems(task, project, target, folder, decision, items);

        HarnessService.AgentHarness own = harness.of(agent.getId());
        List<HarnessResource> all = new ArrayList<>(own.resources());
        Set<String> carried = new HashSet<>();
        own.resources().forEach(r -> carried.add(r.getKey()));
        if (project != null) {
            harness.ofProject(project.getId()).stream().filter(r -> carried.add(r.getKey())).forEach(all::add);
        }
        String taskText = task.getTitle() + "\n" + (task.getDescription() == null ? "" : task.getDescription());
        libraryItems(target, all, taskText, items);
        mcpItems(target, folder, all, items);
        all.stream().filter(r -> r.getKind() == HarnessResource.Kind.TOOL || r.getKind() == HarnessResource.Kind.FRAMEWORK
                        || r.getKind() == HarnessResource.Kind.TEMPLATE_PROVIDER)
                .forEach(r -> items.add(new ReadinessItem("TOOL", r.getKey(), r.getName(), ReadinessStatus.OK, target.isEngine()
                        ? "Nel prompt della run, per nome." : "Nel pacchetto, con la sua configurazione.", null)));
        softwareItems(own.software(), items);
        return done(task, target, agent, items);
    }

    private static Readiness done(Task task, ExecutionTarget target, Agent agent, List<ReadinessItem> items) {
        boolean ready = items.stream().noneMatch(i -> i.status() == ReadinessStatus.BLOCKING);
        return new Readiness(task.getId(), target.key(), target.name(), target.delivery().name(),
                agent == null ? null : agent.getId(), agent == null ? null : agent.getName(), ready, List.copyOf(items));
    }

    // --- binding and model -----------------------------------------------------------------

    private void bindingItems(Agent agent, ExecutionTarget target, String requested, boolean probeEngine,
                              List<ReadinessItem> items) {
        if (target.isEngine()) {
            if (requested == null && !agent.getExecutionTarget().equals(ExecutionTargetCatalog.ENGINE)) {
                String where = targets.find(agent.getExecutionTarget()).map(ExecutionTarget::name).orElse(agent.getExecutionTarget());
                items.add(new ReadinessItem("BINDING", target.key(), target.name(), ReadinessStatus.BLOCKING, "L'agente lavora con " + where
                        + ": consegna la task a quello strumento, oppure scegli un modello dell'AI Engine per la run.", null));
            } else {
                items.add(new ReadinessItem("BINDING", target.key(), target.name(), ReadinessStatus.OK,
                        "La run parte dal control plane; la risposta torna nella console.", null));
            }
            modelForRun(agent, requested, probeEngine, items);
            return;
        }
        if (!agent.getExecutionTarget().equals(target.key())) {
            String own = targets.find(agent.getExecutionTarget()).map(ExecutionTarget::name).orElse(agent.getExecutionTarget());
            items.add(new ReadinessItem("BINDING", "target", target.name(), ReadinessStatus.WARNING,
                    "L'agente è configurato per " + own + ": la consegni a " + target.name() + ".", null));
        }
        Optional<String> noLauncher = target.delivery() == ExecutionTargetCatalog.Delivery.MANUAL
                || target.delivery() == ExecutionTargetCatalog.Delivery.WEB_PASTE ? Optional.empty() : software.launcherUnavailable();
        noLauncher.ifPresent(why -> items.add(new ReadinessItem("BINDING", "launcher", "Launcher", ReadinessStatus.WARNING,
                "La consegna prepara pacchetto e prompt ma non apre " + target.name() + " (" + why + "): aprilo tu nella cartella.",
                null)));
        if (target.software() != null && target.delivery() != ExecutionTargetCatalog.Delivery.WEB_PASTE) {
            Optional<String> problem = software.launchProblem(target.software(), target.link());
            items.add(problem.map(p -> new ReadinessItem("BINDING", target.software(), target.name(), ReadinessStatus.BLOCKING,
                            target.name() + " non si può aprire: " + p + ".", null))
                    .orElse(new ReadinessItem("BINDING", target.software(), target.name(), ReadinessStatus.OK,
                            "Si apre: " + target.howItWorks(), null)));
        } else {
            items.add(new ReadinessItem("BINDING", target.key(), target.name(), ReadinessStatus.OK, target.howItWorks(), null));
        }
        if (target.worksOutsideTheFolder()) {
            // PHASE 37 (ADR-043): which agent of OpenClaw takes the task.
            items.add(publications.find(agent.getId(), target.key())
                    .map(p -> new ReadinessItem("BINDING", "openclaw-agent", "Agente in OpenClaw", ReadinessStatus.OK,
                            "Lavora «" + p.externalId() + "», l'agente di OpenClaw che corrisponde a " + agent.getName() + ".", null))
                    .orElse(new ReadinessItem("BINDING", "openclaw-agent", "Agente in OpenClaw", ReadinessStatus.WARNING,
                            agent.getName() + " non è in OpenClaw: lavorerà l'agente predefinito di OpenClaw, con il ruolo scritto "
                                    + "nel pacchetto. Pubblicalo da Agenti → Ecosistema per dargli identità e memoria sue.", null)));
        }
        AgentBindingService.Binding onTarget = binding.describe(agent.getModel(), target.key(), Map.of());
        if (agent.getModel() == null) {
            items.add(new ReadinessItem("MODEL", "model", "Modello", ReadinessStatus.OK, "Lo sceglie " + target.name() + ".", null));
        } else if (onTarget.problems().isEmpty()) {
            items.add(new ReadinessItem("MODEL", agent.getModel(), onTarget.model().displayName(), ReadinessStatus.OK,
                    "Nel pacchetto: " + onTarget.model().displayName() + ".", null));
        } else {
            items.add(new ReadinessItem("MODEL", agent.getModel(), onTarget.model().displayName(), ReadinessStatus.WARNING,
                    "Il modello dell'agente non si usa con " + target.name() + ": lo sceglie lo strumento, e il pacchetto "
                            + "non lo nomina.", null));
        }
    }

    private void modelForRun(Agent agent, String requested, boolean probeEngine, List<ReadinessItem> items) {
        String model = requested != null ? requested : agent.getModel();
        if (model == null) {
            items.add(new ReadinessItem("MODEL", "model", "Modello", ReadinessStatus.OK,
                    "Quello del catalogo per il ruolo della task, o il default dell'AI Engine.", null));
            return;
        }
        AgentBindingService.Binding onEngine = binding.describe(model, ExecutionTargetCatalog.ENGINE, Map.of());
        if (!onEngine.problems().isEmpty()) {
            items.add(new ReadinessItem("MODEL", model, model, ReadinessStatus.BLOCKING, String.join(" ", onEngine.problems()), null));
            return;
        }
        try {
            costs.requireBudget(model);
        } catch (RuntimeException refused) {
            items.add(new ReadinessItem("MODEL", model, model, ReadinessStatus.BLOCKING, refused.getMessage(), null));
            return;
        }
        if (!probeEngine) {
            items.add(new ReadinessItem("MODEL", model, model, ReadinessStatus.OK, "Eseguibile dall'AI Engine.", null));
            return;
        }
        try {
            EngineClient.ModelList live = engine.models();
            boolean served = live.models().stream().anyMatch(m -> m.id().equals(model) && m.available());
            // The engine says why a provider serves nothing ("ollama:*": Ollama not reachable, no model pulled).
            String provider = model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
            String why = live.models().stream().filter(m -> m.id().equals(provider + ":*") && !m.available())
                    .map(EngineClient.ModelInfo::detail).filter(d -> d != null && !d.isBlank()).findFirst()
                    .map(d -> " (" + d + ")").orElse("");
            items.add(new ReadinessItem("MODEL", model, model, served ? ReadinessStatus.OK : ReadinessStatus.WARNING, served
                    ? "Servito dall'AI Engine." : "L'AI Engine non lo elenca ora" + why
                    + ": la run fallirà se non diventa disponibile.", null));
        } catch (RuntimeException unreachable) {
            items.add(new ReadinessItem("MODEL", model, model, ReadinessStatus.WARNING,
                    "L'AI Engine non risponde: la run fallirà se non torna disponibile.", null));
        }
    }

    // --- context -----------------------------------------------------------------------------

    private void contextItems(Task task, Project project, ExecutionTarget target, Path folder,
                              OrchestrationService.Basics decision, List<ReadinessItem> items) {
        if (project == null) {
            items.add(new ReadinessItem("CONTEXT", "folder", "Cartella", ReadinessStatus.OK, target.isEngine()
                    ? "Nessun progetto: la run ha titolo e descrizione della task."
                    : "Nessun progetto: la consegna prepara una cartella dedicata alla task, con la sua governance.", null));
            return;
        }
        // A project without a folder is the task's own refusal for a handoff (area TASK); a run does not need one.
        if (folder == null && project.getWorkspacePath() != null && !target.isEngine()) {
            items.add(new ReadinessItem("CONTEXT", "folder", "Cartella", ReadinessStatus.OK,
                    "La cartella non esiste ancora: la consegna la prepara.", null));
        }
        if (!target.isEngine()) {
            governance(folder, "AGENTS.md", "la governance che il pacchetto nomina per prima", items);
            if ("CLAUDE.md".equals(target.contextFile())) {
                importsAgents(folder, "CLAUDE.md", "@AGENTS.md", items);
            }
            if (target.key().startsWith("antigravity")) {
                importsAgents(folder, "GEMINI.md", "AGENTS.md", items);
            }
            if (target.contextFile() != null && !List.of("AGENTS.md", "CLAUDE.md").contains(target.contextFile())) {
                items.add(new ReadinessItem("CONTEXT", target.contextFile(), target.contextFile(), ReadinessStatus.OK,
                        exists(folder, target.contextFile()) ? "Presente." : "La consegna lo scrive.", null));
            }
        }
        String taskDocument = task.getDocumentPath();
        for (OrchestrationService.ContextFile file : decision.context()) {
            if (file.path().equals("AGENTS.md") && !target.isEngine()) {
                continue;
            }
            boolean read = !target.isEngine() || file.path().equals(taskDocument)
                    || (task.getPhase() != null && file.path().equals(task.getPhase().getDocumentPath()));
            if (!read) {
                continue;
            }
            ReadinessStatus status = file.exists() ? ReadinessStatus.OK : ReadinessStatus.WARNING;
            String detail = file.exists() ? file.why() + "." : file.why() + ": il file non c'è"
                    + (file.path().equals(taskDocument) && !target.isEngine() ? ", la consegna ne scrive uno minimo." : ".");
            items.add(new ReadinessItem("CONTEXT", file.path(), file.path(), status, detail, null));
        }
        if (taskDocument == null && !target.isEngine()) {
            items.add(new ReadinessItem("CONTEXT", "task-document", "Documento della task", ReadinessStatus.OK,
                    "Task fuori dal piano: la consegna prepara il suo documento.", null));
        }
    }

    private void governance(Path folder, String file, String why, List<ReadinessItem> items) {
        Optional<String> text = read(folder, file);
        if (text.isEmpty()) {
            items.add(new ReadinessItem("CONTEXT", file, file, ReadinessStatus.OK, "Manca: la consegna lo scrive.", null));
        } else if (text.get().contains(".aicos/handoffs/")) {
            items.add(new ReadinessItem("CONTEXT", file, file, ReadinessStatus.OK, "Presente: " + why + ".", null));
        } else if (GeneratedTextUpgrades.upgrade(file, text.get()).isPresent()) {
            items.add(new ReadinessItem("CONTEXT", file, file, ReadinessStatus.OK,
                    "Presente, di una versione precedente: la consegna lo aggiorna perché porti al pacchetto.", null));
        } else {
            items.add(new ReadinessItem("CONTEXT", file, file, ReadinessStatus.WARNING, "Presente ma non porta al pacchetto (è stato "
                    + "modificato): il prompt della consegna nomina comunque il pacchetto.", null));
        }
    }

    private void importsAgents(Path folder, String file, String reference, List<ReadinessItem> items) {
        Optional<String> text = read(folder, file);
        if (text.isEmpty()) {
            items.add(new ReadinessItem("CONTEXT", file, file, ReadinessStatus.OK, "Manca: la consegna lo scrive, con " + reference + ".", null));
        } else {
            boolean imports = text.get().contains(reference);
            items.add(new ReadinessItem("CONTEXT", file, file, imports ? ReadinessStatus.OK : ReadinessStatus.WARNING, imports
                    ? "Presente: importa AGENTS.md." : "Presente ma non importa AGENTS.md: lo strumento legge la "
                    + "governance solo se il pacchetto glielo chiede.", null));
        }
    }

    private Optional<String> read(Path folder, String file) {
        if (folder == null) {
            return Optional.empty();
        }
        try {
            return workspace.readIn(folder, file);
        } catch (RuntimeException refused) {
            return Optional.empty();
        }
    }

    private boolean exists(Path folder, String file) {
        return read(folder, file).isPresent();
    }

    // --- skills and knowledge ------------------------------------------------------------------

    private void libraryItems(ExecutionTarget target, List<HarnessResource> all, String taskText, List<ReadinessItem> items) {
        boolean loadsSkills = !target.isEngine() && hub.loadsSkills(target.key());
        List<HarnessResource> fileBacked = all.stream().filter(r -> SkillLibrary.fileBacked(r.getKind())).toList();
        Set<String> inRun = new HashSet<>();
        if (target.isEngine()) {
            Pertinence.select(taskText, fileBacked, 2).forEach(r -> inRun.add(r.getKey()));
        }
        for (HarnessResource r : fileBacked) {
            String area = r.getKind().name();
            SkillLibrary.FileCheck check = library.check(r).orElseThrow();
            if (check.status() == SkillLibrary.FileCheck.Status.MISSING) {
                items.add(new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "Nessun file nella libreria ("
                        + check.path() + "): all'agente arriva solo la descrizione. Crealo dal Knowledge Hub.", null));
                continue;
            }
            if (check.status() == SkillLibrary.FileCheck.Status.INVALID) {
                items.add(new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "Il file " + check.path()
                        + " non è un documento valido della libreria (" + check.detail() + "): arriva com'è.", null));
                continue;
            }
            if (target.isEngine()) {
                items.add(new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.OK, inRun.contains(r.getKey())
                        ? "Pertinente alla task: nel prompt la sua parte essenziale." : "Nel prompt per nome.", null));
                continue;
            }
            if (r.getKind() == HarnessResource.Kind.KNOWLEDGE) {
                boolean pertinent = !loadsSkills || Pertinence.score(taskText, r) >= Pertinence.THRESHOLD;
                items.add(new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.OK, pertinent
                        ? "Nel pacchetto, da leggere: " + check.path() + "."
                        : "Nel pacchetto, da leggere solo se la task la richiede: " + check.path() + ".", null));
                continue;
            }
            Optional<SkillHubService.ToolCopy> copy = loadsSkills ? hub.copyForTarget(r.getKey(), target.key()) : Optional.empty();
            if (copy.isEmpty()) {
                items.add(new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.OK, "Nel pacchetto, da leggere: " + check.path() + ".", null));
                continue;
            }
            SkillHubService.ToolCopy c = copy.get();
            ReadinessAction deploy = new ReadinessAction("HUB_COPY", r.getKey(), c.appId());
            items.add(switch (c.status()) {
                case "IN_SYNC" -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.OK,
                        "Copia allineata in " + c.appName() + " (" + c.folder() + "): lo strumento la carica.", null);
                case "MODIFIED" -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.OK, "Copia in " + c.appName() + " ("
                        + c.folder() + "), modificata nell'app: lo strumento carica quella.", null);
                case "NONE" -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "Non è nella cartella di "
                        + c.appName() + ": il pacchetto dà il file della libreria. Copiala perché lo strumento la carichi.", deploy);
                case "OUTDATED" -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "La copia in " + c.appName()
                        + " è indietro rispetto alla libreria: aggiornala.", deploy);
                case "FOREIGN" -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "In " + c.appName()
                        + " c'è una skill con questo nome che non viene dal Knowledge Hub (" + c.folder()
                        + "): lo strumento carica quella. Decidi nel Knowledge Hub.", null);
                default -> new ReadinessItem(area, r.getKey(), r.getName(), ReadinessStatus.WARNING, "La copia in " + c.appName()
                        + " è in conflitto con la libreria (modificata in entrambe): risolvi nel Knowledge Hub.", null);
            });
        }
    }

    // --- MCP, software ------------------------------------------------------------------------

    private void mcpItems(ExecutionTarget target, Path folder, List<HarnessResource> all, List<ReadinessItem> items) {
        List<HarnessResource> mcps = all.stream().filter(r -> r.getKind() == HarnessResource.Kind.MCP).toList();
        if (mcps.isEmpty()) {
            return;
        }
        if (target.isEngine()) {
            mcps.forEach(r -> items.add(new ReadinessItem("MCP", r.getKey(), r.getName(), ReadinessStatus.WARNING,
                    "L'AI Engine non usa server MCP: nel prompt arriva solo il nome.", null)));
            return;
        }
        HandoffMcp.Plan plan = HandoffMcp.plan(target.key(), folder == null ? Path.of(".") : folder, mcps);
        Map<String, String> written = new LinkedHashMap<>();
        plan.servers().forEach(s -> written.put(s.key(), s.name()));
        for (HarnessResource r : mcps) {
            if (written.containsKey(r.getKey())) {
                items.add(new ReadinessItem("MCP", r.getKey(), r.getName(), ReadinessStatus.OK, "La consegna lo scrive in "
                        + plan.file() + ", dove " + target.name() + " lo carica.", null));
            } else {
                items.add(new ReadinessItem("MCP", r.getKey(), r.getName(), ReadinessStatus.WARNING, plan.file() == null
                        ? "Da configurare a mano in " + target.name() + ": AI Company OS non scrive la sua configurazione MCP."
                        : "Da configurare a mano: la voce non dice come avviarlo con un solo comando.", null));
            }
        }
    }

    private void softwareItems(List<Software> allowed, List<ReadinessItem> items) {
        Map<String, String> availability = binding.availability();
        for (Software s : allowed) {
            String state = availability.getOrDefault(s.getKey(), Availability.UNKNOWN.name());
            boolean missing = state.equals(Availability.NOT_INSTALLED.name())
                    || state.equals(Availability.INCOMPATIBLE_HARDWARE.name());
            items.add(new ReadinessItem("SOFTWARE", s.getKey(), s.getName(), missing ? ReadinessStatus.WARNING : ReadinessStatus.OK, missing
                    ? "Non risulta installato su questa macchina: l'agente lo trova nel pacchetto ma non potrà usarlo."
                    : "Nel pacchetto e nel prompt della run.", null));
        }
    }

    /**
     * PHASE 36: the refusal of a handoff or a run on what this check blocks. The task's own
     * refusals ({@code TASK}) are left to the rules that already make them, each with its
     * own problem; everything else stops here, before anything is written or opened.
     */
    public void requireReady(Readiness readiness) {
        List<ReadinessItem> blocking = readiness.blockers().stream().filter(i -> !i.area().equals("TASK")).toList();
        if (!blocking.isEmpty()) {
            throw new DeliveryNotReadyException(readiness.targetName(), blocking);
        }
    }
}
