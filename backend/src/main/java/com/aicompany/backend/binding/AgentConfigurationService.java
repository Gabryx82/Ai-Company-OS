package com.aicompany.backend.binding;

import com.aicompany.backend.agent.exception.AgentNameConflictException;
import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentOrigin;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.software.model.Software;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An agent's whole configuration in one place (ADR-025 §3): identity, prompt
 * engineering, harness grouped by kind, allowed software, the binding, and --
 * for seed and template agents -- the initial configuration and what a person
 * has changed since.
 */
@Service
@Transactional
public class AgentConfigurationService {

    /** The fields a baseline records and the console compares, in display order. */
    public static final List<String> COMPARED = List.of("role", "specialization", "description", "capabilities",
            "responsibilities", "systemPrompt", "directives", "limits", "outputFormat", "contextPolicy", "domain",
            "parentId", "model", "executionTarget");

    public record Update(String name, String role, String specialization, String description, String capabilities,
                         String domain, String systemPrompt, String responsibilities, String limits,
                         String outputFormat, String directives, String contextPolicy, Long parentId, String model,
                         String executionTarget) {
    }

    public record Ref(Long id, String name, String role) {
    }

    public record ResourceRef(String key, String name, String kind, String description, String configuration) {
    }

    public record SoftwareRef(String key, String name) {
    }

    public record Configuration(Agent agent, Ref parent, List<Ref> children,
                                Map<String, List<ResourceRef>> resources, List<SoftwareRef> software,
                                AgentBindingService.Binding binding, Map<String, Object> defaults,
                                List<String> modified) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentRepository agents;
    private final HarnessService harness;
    private final AgentBindingService binding;

    public AgentConfigurationService(AgentRepository agents, HarnessService harness, AgentBindingService binding) {
        this.agents = agents;
        this.harness = harness;
        this.binding = binding;
    }

    @Transactional(readOnly = true)
    public Configuration of(Long agentId) {
        HarnessService.AgentHarness h = harness.of(agentId);
        return assemble(h, binding.describe(h.agent()));
    }

    /** Every agent with its binding: the ecosystem view (one detection pass for all). */
    @Transactional(readOnly = true)
    public List<Configuration> all() {
        Map<String, String> availability = binding.availability();
        return harness.all().stream()
                .map(h -> assemble(h, binding.describe(h.agent().getModel(), h.agent().getExecutionTarget(), availability)))
                .toList();
    }

    public Configuration configure(Long agentId, Update u, String by, Precondition precondition) {
        Agent agent = agents.findByIdForUpdate(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        precondition.requireSatisfiedBy(agent.getVersion());
        String name = u.name() == null || u.name().isBlank() ? agent.getName() : u.name().strip();
        if (agents.existsByNormalisedNameAndIdNot(name, agentId)) {
            throw new AgentNameConflictException(name);
        }
        String target = u.executionTarget() == null || u.executionTarget().isBlank()
                ? ExecutionTargetCatalog.ENGINE : u.executionTarget().strip();
        binding.requireValid(u.model(), target);
        harness.requireNoCycle(agentId, u.parentId());
        agent.configureProfile(u.parentId(), u.domain(), u.systemPrompt(), u.responsibilities(), u.limits(),
                u.outputFormat(), u.directives(), u.contextPolicy());
        agent.updateDetails(name, u.role(), u.specialization(), blankToNull(u.model()));
        agent.configureBinding(u.description(), u.capabilities(), u.model(), target);
        if (agent.getOrigin() != AgentOrigin.USER) {
            agent.markCustomized(by);
        }
        agents.flush();
        return of(agentId);
    }

    /** Back to the seed or template configuration. Resources and software are left as they are. */
    public Configuration reset(Long agentId, String by, Precondition precondition) {
        Agent agent = agents.findByIdForUpdate(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        precondition.requireSatisfiedBy(agent.getVersion());
        if (agent.getBaseline() == null) {
            throw BindingProblemException.noBaseline();
        }
        JsonNode base = JSON.readTree(agent.getBaseline());
        Long parent = base.path("parentId").isNumber() ? base.path("parentId").asLong() : null;
        if (parent != null && !agents.existsById(parent)) {
            parent = null;
        }
        harness.requireNoCycle(agentId, parent);
        agent.configureProfile(parent, text(base, "domain"), text(base, "systemPrompt"), text(base, "responsibilities"),
                text(base, "limits"), text(base, "outputFormat"), text(base, "directives"), text(base, "contextPolicy"));
        agent.updateDetails(agent.getName(), Objects.requireNonNullElse(text(base, "role"), agent.getRole()),
                Objects.requireNonNullElse(text(base, "specialization"), agent.getSpecialization()), text(base, "model"));
        agent.configureBinding(text(base, "description"), text(base, "capabilities"), text(base, "model"),
                Objects.requireNonNullElse(text(base, "executionTarget"), ExecutionTargetCatalog.ENGINE));
        agent.markCustomized(by);
        agents.flush();
        return of(agentId);
    }

    /** The baseline JSON of an agent as it is now: what a template install records. */
    public static String baselineOf(Agent agent, List<String> resources, List<String> software) {
        ObjectNode node = JSON.createObjectNode();
        current(agent).forEach((key, value) -> {
            if (value == null) {
                node.putNull(key);
            } else if (value instanceof Long number) {
                node.put(key, number);
            } else {
                node.put(key, value.toString());
            }
        });
        node.putPOJO("resources", resources);
        node.putPOJO("software", software);
        return JSON.writeValueAsString(node);
    }

    // --- assembly ---------------------------------------------------------------------

    private Configuration assemble(HarnessService.AgentHarness h, AgentBindingService.Binding b) {
        Agent agent = h.agent();
        Ref parent = agent.getParentId() == null ? null : agents.findById(agent.getParentId())
                .map(p -> new Ref(p.getId(), p.getName(), p.getRole())).orElse(null);
        List<Ref> children = agents.findAllByOrderByIdAsc().stream()
                .filter(a -> agent.getId().equals(a.getParentId()))
                .map(a -> new Ref(a.getId(), a.getName(), a.getRole())).toList();
        Map<String, List<ResourceRef>> resources = new LinkedHashMap<>();
        for (HarnessResource.Kind kind : HarnessResource.Kind.values()) {
            resources.put(kind.name(), new ArrayList<>());
        }
        for (HarnessResource r : h.resources()) {
            resources.get(r.getKind().name()).add(new ResourceRef(r.getKey(), r.getName(), r.getKind().name(),
                    r.getDescription(), r.getConfiguration()));
        }
        List<SoftwareRef> software = h.software().stream().map(s -> new SoftwareRef(s.getKey(), s.getName())).toList();

        Map<String, Object> defaults = null;
        List<String> modified = List.of();
        if (agent.getBaseline() != null) {
            JsonNode base = JSON.readTree(agent.getBaseline());
            defaults = new LinkedHashMap<>();
            List<String> changed = new ArrayList<>();
            Map<String, Object> now = current(agent);
            for (String field : COMPARED) {
                Object initial = field.equals("parentId")
                        ? (base.path(field).isNumber() ? base.path(field).asLong() : null)
                        : text(base, field);
                if (field.equals("executionTarget") && initial == null) {
                    initial = ExecutionTargetCatalog.ENGINE;
                }
                defaults.put(field, initial);
                if (!Objects.equals(normalize(initial), normalize(now.get(field)))) {
                    changed.add(field);
                }
            }
            List<String> baseResources = strings(base.path("resources"));
            List<String> baseSoftware = strings(base.path("software"));
            if (base.has("resources")) {
                defaults.put("resources", baseResources);
                List<String> nowResources = h.resources().stream().map(HarnessResource::getKey).sorted().toList();
                if (!baseResources.stream().sorted().toList().equals(nowResources)) {
                    changed.add("resources");
                }
            }
            if (base.has("software")) {
                defaults.put("software", baseSoftware);
                List<String> nowSoftware = h.software().stream().map(Software::getKey).sorted().toList();
                if (!baseSoftware.stream().sorted().toList().equals(nowSoftware)) {
                    changed.add("software");
                }
            }
            modified = List.copyOf(changed);
        }
        return new Configuration(agent, parent, children, resources, software, b, defaults, modified);
    }

    private static Map<String, Object> current(Agent a) {
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("role", a.getRole());
        now.put("specialization", a.getSpecialization());
        now.put("description", a.getDescription());
        now.put("capabilities", a.getCapabilities());
        now.put("responsibilities", a.getResponsibilities());
        now.put("systemPrompt", a.getSystemPrompt());
        now.put("directives", a.getDirectives());
        now.put("limits", a.getLimits());
        now.put("outputFormat", a.getOutputFormat());
        now.put("contextPolicy", a.getContextPolicy());
        now.put("domain", a.getDomain());
        now.put("parentId", a.getParentId());
        now.put("model", a.getModel());
        now.put("executionTarget", a.getExecutionTarget());
        return now;
    }

    private static Object normalize(Object value) {
        if (value instanceof String text) {
            String stripped = text.replace("\r\n", "\n").strip();
            return stripped.isEmpty() ? null : stripped;
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> out.add(item.asString()));
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Lines of a text field, for callers that want a list. */
    public static List<String> lines(String text) {
        return text == null ? List.of() : Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    Instant now() {
        return Instant.now();
    }
}
