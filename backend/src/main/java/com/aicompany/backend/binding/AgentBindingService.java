package com.aicompany.backend.binding;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelProvider;
import com.aicompany.backend.llm.model.ProviderStatus;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.llm.repository.ModelProviderRepository;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.service.SoftwareService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent -> Model -> Provider -> Execution Target (ADR-025 §1). Four concepts,
 * kept apart: the model is the agent's column, the provider is the model's (from
 * the catalog), the target is the agent's other column and names the software
 * it opens. This service puts them side by side, says whether they go
 * together, and refuses the combinations that cannot work.
 */
@Service
@Transactional(readOnly = true)
public class AgentBindingService {

    public record ModelRef(String key, String displayName, String role, String lifecycle, String replacedBy,
                           boolean catalogued, boolean engineRunnable) {
    }

    public record ProviderRef(String key, String name, String kind, String billing, String status) {
    }

    public record TargetRef(String key, String name, String delivery, String software, String availability,
                            List<String> providers, String contextFile, String howItWorks) {
    }

    /** The whole chain, with whether it holds and what to watch. */
    public record Binding(ModelRef model, ProviderRef provider, TargetRef target, boolean valid,
                          List<String> problems, List<String> warnings, String summary) {
    }

    private final ExecutionTargetCatalog targets;
    private final LlmModelRepository models;
    private final ModelProviderRepository providers;
    private final SoftwareService software;

    public AgentBindingService(ExecutionTargetCatalog targets, LlmModelRepository models,
                               ModelProviderRepository providers, SoftwareService software) {
        this.targets = targets;
        this.models = models;
        this.providers = providers;
        this.software = software;
    }

    public Binding describe(Agent agent) {
        return describe(agent.getModel(), agent.getExecutionTarget(), availability());
    }

    /** One detection pass for many agents: the ecosystem view asks for all of them. */
    public Map<String, String> availability() {
        return software.findAll().stream().collect(Collectors.toMap(d -> d.software().getKey(),
                d -> d.detection().availability().name(), (a, b) -> a));
    }

    public Binding describe(String modelKey, String targetKey, Map<String, String> availability) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        ExecutionTarget target = targets.find(targetKey).orElse(null);
        TargetRef targetRef;
        if (target == null) {
            problems.add("L'execution target «" + targetKey + "» non esiste nel catalogo.");
            targetRef = new TargetRef(targetKey, targetKey, null, null, null, List.of(), null, null);
        } else {
            String state = target.software() == null ? null : availability.getOrDefault(target.software(), Availability.NOT_INSTALLED.name());
            targetRef = new TargetRef(target.key(), target.name(), target.delivery().name(), target.software(), state,
                    target.providers(), target.contextFile(), target.howItWorks());
            if (Availability.NOT_INSTALLED.name().equals(state) || Availability.INCOMPATIBLE_HARDWARE.name().equals(state)) {
                warnings.add(target.name() + " non risulta installato su questa macchina.");
            }
        }

        ModelRef modelRef = null;
        ProviderRef providerRef = null;
        if (modelKey == null || modelKey.isBlank()) {
            if (target != null && target.isEngine()) {
                warnings.add("Nessun modello scelto: l'AI Engine userà il suo modello di default.");
            }
        } else {
            Optional<LlmModel> model = models.findByKey(modelKey);
            String providerKey = model.map(LlmModel::getProviderKey)
                    .orElse(modelKey.contains(":") ? modelKey.substring(0, modelKey.indexOf(':')) : modelKey);
            Optional<ModelProvider> provider = providers.findByKey(providerKey);
            boolean engineRunnable = provider.map(p -> p.getEngineProvider() != null).orElse(false);
            modelRef = new ModelRef(modelKey, model.map(LlmModel::getDisplayName).orElse(modelKey),
                    model.map(m -> m.getRole().name()).orElse(null), model.map(m -> m.getLifecycle().name()).orElse(null),
                    model.map(LlmModel::getReplacedBy).orElse(null), model.isPresent(), engineRunnable);
            providerRef = provider.map(p -> new ProviderRef(p.getKey(), p.getName(), p.getKind().name(),
                    p.getBilling().name(), p.getStatus().name())).orElse(new ProviderRef(providerKey, providerKey,
                    null, null, null));

            if (model.isEmpty()) {
                warnings.add("Il modello «" + modelKey + "» non è nel catalogo dei modelli.");
            } else if (model.get().getLifecycle() == ModelLifecycle.DEPRECATED) {
                warnings.add(model.get().getDisplayName() + " è superato"
                        + (model.get().getReplacedBy() == null ? "." : ": il sostituto è " + model.get().getReplacedBy() + "."));
            }
            if (provider.isPresent() && provider.get().getStatus() != ProviderStatus.ENABLED) {
                warnings.add("Il provider " + provider.get().getName() + " è " + (provider.get().getStatus()
                        == ProviderStatus.DISABLED ? "spento (manca la chiave o la decisione di abilitarlo)."
                        : "incompatibile con questa macchina."));
            }
            if (provider.isEmpty()) {
                warnings.add("Il provider «" + providerKey + "» non è nel catalogo: la compatibilità con "
                        + targetRef.name() + " non è verificabile.");
            } else if (target != null && !target.providers().contains(providerKey)) {
                problems.add(target.isEngine()
                        ? "L'AI Engine non può eseguire " + modelRef.displayName() + ": è un modello usato tramite la sua app. "
                                + "Scegli come execution target l'app o la CLI corrispondente."
                        : target.name() + " non usa modelli di " + providerRef.name() + ". Modelli compatibili: provider "
                                + String.join(", ", target.providers()) + ".");
            }
        }

        String summary = (modelRef == null ? (target != null && target.isEngine() ? "modello di default" : "modello scelto nell'app")
                : modelRef.displayName())
                + (providerRef == null ? "" : " · " + providerRef.name())
                + " → " + targetRef.name();
        return new Binding(modelRef, providerRef, targetRef, problems.isEmpty(), List.copyOf(problems),
                List.copyOf(warnings), summary);
    }

    /** Refuses a binding that cannot work. Warnings do not refuse. */
    public void requireValid(String modelKey, String targetKey) {
        if (targetKey != null && !targetKey.isBlank() && targets.find(targetKey).isEmpty()) {
            throw BindingProblemException.invalid("executionTarget", "L'execution target «" + targetKey + "» non esiste.");
        }
        Binding binding = describe(modelKey, targetKey, Map.of());
        if (!binding.valid()) {
            throw BindingProblemException.invalid("model", String.join(" ", binding.problems()));
        }
    }

    /**
     * A run through the AI Engine (ADR-016) asked of this agent. Refused when the
     * agent works through an application or a CLI and no engine model was asked
     * for explicitly, and whenever the model that would be sent is one the engine
     * cannot run.
     */
    public void requireEngineRun(Agent agent, String requestedModel) {
        ExecutionTarget target = targets.find(agent.getExecutionTarget()).orElse(null);
        if (requestedModel == null && target != null && !target.isEngine()) {
            throw BindingProblemException.worksElsewhere("L'agente " + agent.getName() + " lavora con " + target.name()
                    + ": prepara l'handoff per quello strumento, oppure scegli un modello dell'AI Engine per questa run.");
        }
        String model = requestedModel != null ? requestedModel : agent.getModel();
        if (model == null) {
            return;
        }
        String providerKey = models.findByKey(model).map(LlmModel::getProviderKey)
                .orElse(model.contains(":") ? model.substring(0, model.indexOf(':')) : model);
        Optional<ModelProvider> provider = providers.findByKey(providerKey);
        if (provider.isPresent() && provider.get().getEngineProvider() == null) {
            throw BindingProblemException.worksElsewhere(model + " si usa tramite la sua applicazione ("
                    + provider.get().getName() + "): l'AI Engine non può eseguirlo. Prepara l'handoff.");
        }
    }

    public List<ExecutionTarget> targets() {
        return targets.all();
    }

    public Map<String, ExecutionTarget> targetsByKey() {
        return targets.all().stream().collect(Collectors.toMap(ExecutionTarget::key, Function.identity()));
    }
}
