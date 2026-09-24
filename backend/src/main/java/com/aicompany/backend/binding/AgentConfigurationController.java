package com.aicompany.backend.binding;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.binding.AgentConfigurationService.Configuration;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An agent's configuration and binding (ADR-025), and the execution targets it
 * can be bound to.
 */
@RestController
public class AgentConfigurationController {

    private final AgentConfigurationService configurations;
    private final AgentBindingService bindings;

    public AgentConfigurationController(AgentConfigurationService configurations, AgentBindingService bindings) {
        this.configurations = configurations;
        this.bindings = bindings;
    }

    public record ConfigurationRequest(
            @Size(max = 255) String name,
            @NotBlank @Size(max = 255) String role,
            @NotBlank @Size(max = 255) String specialization,
            @Size(max = 2000) String description,
            @Size(max = 2000) String capabilities,
            @Size(max = 120) String domain,
            @Size(max = 8000) String systemPrompt,
            @Size(max = 4000) String responsibilities,
            @Size(max = 4000) String limits,
            @Size(max = 2000) String outputFormat,
            @Size(max = 8000) String directives,
            @Size(max = 2000) String contextPolicy,
            Long parentId,
            @Size(max = 200) String model,
            @Size(max = 64) String executionTarget) {
    }

    public record ConfigurationResponse(
            Long id, String name, String role, String specialization, String description, List<String> capabilities,
            String status, AgentConfigurationService.Ref parent, List<AgentConfigurationService.Ref> children,
            String domain, String systemPrompt, String responsibilities, String limits, String outputFormat,
            List<String> directives, String contextPolicy,
            Map<String, List<AgentConfigurationService.ResourceRef>> resources,
            List<AgentConfigurationService.SoftwareRef> software,
            String model, String executionTarget, AgentBindingService.Binding binding,
            String origin, Map<String, Object> defaults, List<String> modified,
            Instant customizedAt, String customizedBy, long version) {

        static ConfigurationResponse from(Configuration c) {
            Agent a = c.agent();
            return new ConfigurationResponse(a.getId(), a.getName(), a.getRole(), a.getSpecialization(),
                    a.getDescription(), AgentConfigurationService.lines(a.getCapabilities()), a.getStatus().name(),
                    c.parent(), c.children(), a.getDomain(), a.getSystemPrompt(), a.getResponsibilities(),
                    a.getLimits(), a.getOutputFormat(), AgentConfigurationService.lines(a.getDirectives()),
                    a.getContextPolicy(), c.resources(), c.software(), a.getModel(), a.getExecutionTarget(),
                    c.binding(), a.getOrigin().name(), c.defaults(), c.modified(), a.getCustomizedAt(),
                    a.getCustomizedBy(), a.getVersion());
        }
    }

    @GetMapping("/api/agents/{id}/configuration")
    public ResponseEntity<ConfigurationResponse> configuration(@PathVariable Long id) {
        Configuration c = configurations.of(id);
        return ResponseEntity.ok().eTag(ETags.of(c.agent().getVersion())).body(ConfigurationResponse.from(c));
    }

    /** Everything at once, under the agent's tag (ADR-009). Harness links keep their own set-membership routes. */
    @PutMapping("/api/agents/{id}/configuration")
    public ResponseEntity<ConfigurationResponse> configure(@PathVariable Long id,
                                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                           @Valid @RequestBody ConfigurationRequest r,
                                                           Authentication caller) {
        Configuration c = configurations.configure(id, new AgentConfigurationService.Update(r.name(), r.role(),
                r.specialization(), r.description(), r.capabilities(), r.domain(), r.systemPrompt(), r.responsibilities(),
                r.limits(), r.outputFormat(), r.directives(), r.contextPolicy(), r.parentId(), r.model(),
                r.executionTarget()), caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(c.agent().getVersion())).body(ConfigurationResponse.from(c));
    }

    /** Back to the seed or template configuration. */
    @PostMapping("/api/agents/{id}/configuration/reset")
    public ResponseEntity<ConfigurationResponse> reset(@PathVariable Long id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       Authentication caller) {
        Configuration c = configurations.reset(id, caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(c.agent().getVersion())).body(ConfigurationResponse.from(c));
    }

    /** Every agent with its binding: Agent -> Model -> Provider -> Execution Target, for the ecosystem view. */
    @GetMapping("/api/ecosystem/bindings")
    public List<ConfigurationResponse> bindings() {
        return configurations.all().stream().map(ConfigurationResponse::from).toList();
    }

    @GetMapping("/api/execution-targets")
    public List<ExecutionTarget> targets() {
        return bindings.targets();
    }

    /** What a model on a target would mean, before saving: the console previews a binding with this. */
    @GetMapping("/api/execution-targets/{key}/check")
    public AgentBindingService.Binding check(@PathVariable String key,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String model) {
        if (bindings.targetsByKey().get(key) == null) {
            throw BindingProblemException.targetNotFound(key);
        }
        return bindings.describe(model, key, bindings.availability());
    }
}
