package com.aicompany.backend.harness.controller;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.AgentTemplates;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.software.model.Software;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** The agent ecosystem (ADR-023): profiles, harness links, and the Explorer's catalog. */
@RestController
public class HarnessController {

    public record ResourceResponse(Long id, String key, HarnessResource.Kind kind, String name, String description,
                                   List<String> tags, String sourceUrl, String searchUrl, String configuration,
                                   HarnessResource.Origin origin, boolean fileBacked, String filePath, long version) {
        public static ResourceResponse from(HarnessResource r) {
            return new ResourceResponse(r.getId(), r.getKey(), r.getKind(), r.getName(), r.getDescription(), r.getTags(),
                    r.getSourceUrl(), r.getSearchUrl(), r.getConfiguration(), r.getOrigin(),
                    com.aicompany.backend.harness.library.SkillLibrary.fileBacked(r.getKind()), r.getFilePath(),
                    r.getVersion());
        }
    }

    public record ResourceRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$", message = "must be 2-64 lower-case letters, digits or dashes")
            String key,
            @NotNull HarnessResource.Kind kind,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @Size(max = 20) List<@Size(max = 40) String> tags,
            @Size(max = 500) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String sourceUrl,
            @Size(max = 500) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String searchUrl,
            @Size(max = 4000) String configuration) {
    }

    public record ProfileRequest(Long parentId, @Size(max = 120) String domain, @Size(max = 8000) String systemPrompt,
                                 @Size(max = 4000) String responsibilities, @Size(max = 4000) String limits,
                                 @Size(max = 2000) String outputFormat, @Size(max = 8000) String directives,
                                 @Size(max = 2000) String contextPolicy) {
    }

    public record SoftwareRef(String key, String name) {
    }

    public record AgentProfileResponse(Long agentId, String name, String role, String specialization, String model,
                                       boolean active, Long parentId, String domain, String systemPrompt,
                                       String responsibilities, String limits, String outputFormat,
                                       List<String> directives, String contextPolicy, List<ResourceResponse> resources,
                                       List<SoftwareRef> software, long version) {
        public static AgentProfileResponse from(HarnessService.AgentHarness h) {
            Agent a = h.agent();
            return new AgentProfileResponse(a.getId(), a.getName(), a.getRole(), a.getSpecialization(), a.getModel(),
                    a.isActive(), a.getParentId(), a.getDomain(), a.getSystemPrompt(), a.getResponsibilities(),
                    a.getLimits(), a.getOutputFormat(),
                    a.getDirectives() == null ? List.of()
                            : Arrays.stream(a.getDirectives().split("\n")).map(String::strip).filter(d -> !d.isEmpty()).toList(),
                    a.getContextPolicy(), h.resources().stream().map(ResourceResponse::from).toList(),
                    h.software().stream().map((Software s) -> new SoftwareRef(s.getKey(), s.getName())).toList(),
                    a.getVersion());
        }
    }

    private final HarnessService service;
    private final AgentTemplates templates;

    public HarnessController(HarnessService service, AgentTemplates templates) {
        this.service = service;
        this.templates = templates;
    }

    // --- agent templates (directive §15, §22) ------------------------------------

    @GetMapping("/api/agent-templates")
    public List<AgentTemplates.Template> agentTemplates() {
        return templates.all();
    }

    /** Creates the template's agent and sub-agents; existing names are left untouched. */
    @PostMapping("/api/agent-templates/{key}/install")
    public List<AgentTemplates.Installed> install(@PathVariable String key) {
        return templates.install(key);
    }

    @PostMapping("/api/agent-templates/install-all")
    public List<AgentTemplates.Installed> installAll() {
        return templates.installAll();
    }

    // --- the Explorer -------------------------------------------------------------

    @GetMapping("/api/resources")
    public List<ResourceResponse> search(@RequestParam(required = false) HarnessResource.Kind kind,
                                         @RequestParam(required = false) String q) {
        return service.search(kind, q).stream().map(ResourceResponse::from).toList();
    }

    @PostMapping("/api/resources")
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest r) {
        HarnessResource created = service.create(new HarnessResource(r.key(), r.kind(), r.name(), r.description(),
                r.tags(), r.sourceUrl(), r.searchUrl(), r.configuration()));
        return ResponseEntity.created(URI.create("/api/resources/" + created.getKey())).body(ResourceResponse.from(created));
    }

    // --- agents -----------------------------------------------------------------

    @GetMapping("/api/agent-profiles")
    public List<AgentProfileResponse> profiles() {
        return service.all().stream().map(AgentProfileResponse::from).toList();
    }

    @GetMapping("/api/agents/{id}/profile")
    public ResponseEntity<AgentProfileResponse> profile(@PathVariable Long id) {
        HarnessService.AgentHarness harness = service.of(id);
        return ResponseEntity.ok().eTag(ETags.of(harness.agent().getVersion())).body(AgentProfileResponse.from(harness));
    }

    /** Prompt engineering and hierarchy. Takes the agent's tag (ADR-009). */
    @PutMapping("/api/agents/{id}/profile")
    public ResponseEntity<AgentProfileResponse> configure(@PathVariable Long id,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @Valid @RequestBody ProfileRequest r) {
        service.configure(id, new HarnessService.Profile(r.parentId(), r.domain(), r.systemPrompt(),
                r.responsibilities(), r.limits(), r.outputFormat(), r.directives(), r.contextPolicy()),
                Precondition.fromHeader(ifMatch));
        return profile(id);
    }

    @PutMapping("/api/agents/{id}/resources/{key}")
    public ResponseEntity<Void> attach(@PathVariable Long id, @PathVariable String key) {
        service.attach(id, key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/agents/{id}/resources/{key}")
    public ResponseEntity<Void> detach(@PathVariable Long id, @PathVariable String key) {
        service.detach(id, key);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/agents/{id}/software/{key}")
    public ResponseEntity<Void> allow(@PathVariable Long id, @PathVariable String key) {
        service.allowSoftware(id, key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/agents/{id}/software/{key}")
    public ResponseEntity<Void> disallow(@PathVariable Long id, @PathVariable String key) {
        service.disallowSoftware(id, key);
        return ResponseEntity.noContent().build();
    }

    // --- projects ---------------------------------------------------------------

    @GetMapping("/api/projects/{id}/resources")
    public List<ResourceResponse> projectResources(@PathVariable Long id) {
        return service.ofProject(id).stream().map(ResourceResponse::from).toList();
    }

    @PutMapping("/api/projects/{id}/resources/{key}")
    public ResponseEntity<Void> adopt(@PathVariable Long id, @PathVariable String key) {
        service.adopt(id, key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/projects/{id}/resources/{key}")
    public ResponseEntity<Void> drop(@PathVariable Long id, @PathVariable String key) {
        service.drop(id, key);
        return ResponseEntity.noContent().build();
    }
}
