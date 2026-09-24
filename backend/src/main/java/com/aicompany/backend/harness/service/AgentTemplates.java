package com.aicompany.backend.harness.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.agent.service.AgentService;
import com.aicompany.backend.harness.exception.HarnessResourceNotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent templates (directive §15 and §22): ready-made agents with their prompt
 * engineering, harness, software and sub-agents, installed on request from
 * {@code classpath:catalog/agent-templates.json}.
 *
 * <p>Templates rather than seed rows: the agents are the operator's data, and
 * installing them is the operator's act. An agent whose name already exists is
 * left exactly as it is and reported, never overwritten.
 */
@Service
public class AgentTemplates {

    public record Template(String key, String name, String role, String specialization, String model, String domain,
                           String systemPrompt, String responsibilities, String limits, String outputFormat,
                           List<String> directives, List<String> resources, List<String> software,
                           List<Template> children) {
    }

    public enum Outcome { CREATED, EXISTING }

    public record Installed(String name, Long agentId, Long parentId, Outcome outcome) {
    }

    private final List<Template> templates;
    private final AgentService agentService;
    private final AgentRepository agents;
    private final HarnessService harness;

    public AgentTemplates(AgentService agentService, AgentRepository agents, HarnessService harness) {
        this.agentService = agentService;
        this.agents = agents;
        this.harness = harness;
        JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = new ClassPathResource("catalog/agent-templates.json").getInputStream()) {
            this.templates = List.of(json.readValue(in, Template[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("catalog/agent-templates.json is unreadable", e);
        }
    }

    @Transactional(readOnly = true)
    public List<Template> all() {
        return templates;
    }

    /** Installs one template and its sub-agents, in one transaction. */
    @Transactional
    public List<Installed> install(String key) {
        Template template = templates.stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> new HarnessResourceNotFoundException("No agent template '" + key + "'"));
        List<Installed> out = new ArrayList<>();
        install(template, null, out);
        return out;
    }

    /** Installs every template: the whole base ecosystem. */
    @Transactional
    public List<Installed> installAll() {
        List<Installed> out = new ArrayList<>();
        templates.forEach(template -> install(template, null, out));
        return out;
    }

    private void install(Template t, Long parentId, List<Installed> out) {
        Optional<Agent> existing = agents.findAllByOrderByIdAsc().stream()
                .filter(a -> a.getName().equalsIgnoreCase(t.name())).findFirst();
        Agent agent;
        if (existing.isPresent()) {
            agent = existing.get();
            out.add(new Installed(agent.getName(), agent.getId(), agent.getParentId(), Outcome.EXISTING));
        } else {
            agent = agentService.create(t.name(), t.role(), t.specialization(), t.model());
            agent.configureProfile(parentId, t.domain(), t.systemPrompt(), t.responsibilities(), t.limits(),
                    t.outputFormat(), t.directives() == null ? null : String.join("\n", t.directives()), null);
            agents.flush();
            for (String resource : t.resources() == null ? List.<String>of() : t.resources()) {
                harness.attach(agent.getId(), resource);
            }
            for (String software : t.software() == null ? List.<String>of() : t.software()) {
                harness.allowSoftware(agent.getId(), software);
            }
            out.add(new Installed(agent.getName(), agent.getId(), parentId, Outcome.CREATED));
        }
        for (Template child : t.children() == null ? List.<Template>of() : t.children()) {
            install(child, agent.getId(), out);
        }
    }
}
