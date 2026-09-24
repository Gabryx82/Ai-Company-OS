package com.aicompany.backend.harness.service;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.harness.exception.AgentHierarchyCycleException;
import com.aicompany.backend.harness.exception.HarnessResourceKeyConflictException;
import com.aicompany.backend.harness.exception.HarnessResourceNotFoundException;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.repository.HarnessResourceRepository;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.software.exception.SoftwareNotFoundException;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The agent ecosystem (ADR-023): each agent's prompt engineering and place in
 * the hierarchy, its harness, the software it may use, and the catalog the
 * Explorer searches.
 *
 * <p>Profile writes follow ADR-009 on the agent row. Links -- agent to
 * resource, agent to software, project to resource -- are set membership: an
 * idempotent PUT adds, an idempotent DELETE removes, and neither mutates the
 * agent or the project, so neither takes their tag.
 */
@Service
@Transactional
public class HarnessService {

    public record Profile(Long parentId, String domain, String systemPrompt, String responsibilities, String limits,
                          String outputFormat, String directives, String contextPolicy) {
    }

    public record AgentHarness(Agent agent, List<HarnessResource> resources, List<Software> software) {
    }

    private final AgentRepository agents;
    private final HarnessResourceRepository resources;
    private final SoftwareRepository software;
    private final ProjectRepository projects;
    private final JdbcTemplate jdbc;

    public HarnessService(AgentRepository agents, HarnessResourceRepository resources, SoftwareRepository software,
                          ProjectRepository projects, JdbcTemplate jdbc) {
        this.agents = agents;
        this.resources = resources;
        this.software = software;
        this.projects = projects;
        this.jdbc = jdbc;
    }

    // --- the catalog ------------------------------------------------------------

    /** The Explorer: by kind, and by words in the name, description or tags. */
    @Transactional(readOnly = true)
    public List<HarnessResource> search(HarnessResource.Kind kind, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return resources.findAllByOrderByKindAscNameAsc().stream()
                .filter(r -> kind == null || r.getKind() == kind)
                .filter(r -> needle.isEmpty() || (r.getName() + " " + (r.getDescription() == null ? "" : r.getDescription())
                        + " " + String.join(" ", r.getTags())).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    public HarnessResource create(HarnessResource resource) {
        if (resources.existsByKey(resource.getKey())) {
            throw new HarnessResourceKeyConflictException("The catalog already has a resource '" + resource.getKey() + "'");
        }
        try {
            return resources.saveAndFlush(resource);
        } catch (DataIntegrityViolationException raced) {
            throw new HarnessResourceKeyConflictException("The catalog already has a resource '" + resource.getKey() + "'");
        }
    }

    // --- agents -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AgentHarness> all() {
        Map<Long, List<HarnessResource>> byAgent = linkedResources("agent_resources", "agent_id");
        Map<Long, List<Software>> softwareByAgent = linkedSoftware();
        return agents.findAllByOrderByIdAsc().stream()
                .map(a -> new AgentHarness(a, byAgent.getOrDefault(a.getId(), List.of()),
                        softwareByAgent.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentHarness of(Long agentId) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        return new AgentHarness(agent, linkedResources("agent_resources", "agent_id").getOrDefault(agentId, List.of()),
                linkedSoftware().getOrDefault(agentId, List.of()));
    }

    /** ADR-009 on the agent row; the parent is checked for cycles under that lock. */
    public Agent configure(Long agentId, Profile profile, Precondition precondition) {
        Agent agent = agents.findByIdForUpdate(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        precondition.requireSatisfiedBy(agent.getVersion());
        if (profile.parentId() != null) {
            requireNoCycle(agentId, profile.parentId());
        }
        agent.configureProfile(profile.parentId(), profile.domain(), profile.systemPrompt(), profile.responsibilities(),
                profile.limits(), profile.outputFormat(), profile.directives(), profile.contextPolicy());
        agents.flush();
        return agent;
    }

    public void attach(Long agentId, String resourceKey) {
        requireAgent(agentId);
        HarnessResource resource = resource(resourceKey);
        jdbc.update("INSERT INTO agent_resources (agent_id, resource_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                agentId, resource.getId());
    }

    public void detach(Long agentId, String resourceKey) {
        requireAgent(agentId);
        jdbc.update("DELETE FROM agent_resources WHERE agent_id = ? AND resource_id = ?", agentId,
                resource(resourceKey).getId());
    }

    public void allowSoftware(Long agentId, String softwareKey) {
        requireAgent(agentId);
        Software s = software.findByKey(softwareKey).orElseThrow(() -> new SoftwareNotFoundException(softwareKey));
        jdbc.update("INSERT INTO agent_software (agent_id, software_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                agentId, s.getId());
    }

    public void disallowSoftware(Long agentId, String softwareKey) {
        requireAgent(agentId);
        Software s = software.findByKey(softwareKey).orElseThrow(() -> new SoftwareNotFoundException(softwareKey));
        jdbc.update("DELETE FROM agent_software WHERE agent_id = ? AND software_id = ?", agentId, s.getId());
    }

    // --- projects ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<HarnessResource> ofProject(Long projectId) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return linkedResources("project_resources", "project_id").getOrDefault(projectId, List.of());
    }

    public void adopt(Long projectId, String resourceKey) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        jdbc.update("INSERT INTO project_resources (project_id, resource_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                projectId, resource(resourceKey).getId());
    }

    public void drop(Long projectId, String resourceKey) {
        projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        jdbc.update("DELETE FROM project_resources WHERE project_id = ? AND resource_id = ?", projectId,
                resource(resourceKey).getId());
    }

    // --- helpers ------------------------------------------------------------------

    /** Walks up from the proposed parent; meeting the agent itself means a cycle. */
    private void requireNoCycle(Long agentId, Long parentId) {
        Set<Long> seen = new HashSet<>();
        Long current = parentId;
        while (current != null) {
            if (current.equals(agentId)) {
                throw new AgentHierarchyCycleException(
                        "Agent " + parentId + " is agent " + agentId + " or one of its sub-agents");
            }
            if (!seen.add(current)) {
                break; // an existing cycle elsewhere is not this write's to report
            }
            Long lookup = current;
            current = agents.findById(lookup).orElseThrow(() -> new AgentNotFoundException(lookup)).getParentId();
        }
    }

    private void requireAgent(Long agentId) {
        if (!agents.existsById(agentId)) {
            throw new AgentNotFoundException(agentId);
        }
    }

    private HarnessResource resource(String key) {
        return resources.findByKey(key)
                .orElseThrow(() -> new HarnessResourceNotFoundException("No resource '" + key + "' in the catalog"));
    }

    private Map<Long, List<HarnessResource>> linkedResources(String table, String ownerColumn) {
        Map<Long, HarnessResource> byId = resources.findAll().stream()
                .collect(Collectors.toMap(HarnessResource::getId, r -> r));
        return jdbc.query("SELECT " + ownerColumn + ", resource_id FROM " + table + " ORDER BY created_at",
                        (rs, row) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .stream()
                .filter(link -> byId.containsKey(link.getValue()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(link -> byId.get(link.getValue()), Collectors.toList())));
    }

    private Map<Long, List<Software>> linkedSoftware() {
        Map<Long, Software> byId = software.findAll().stream().collect(Collectors.toMap(Software::getId, s -> s));
        return jdbc.query("SELECT agent_id, software_id FROM agent_software ORDER BY created_at",
                        (rs, row) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .stream()
                .filter(link -> byId.containsKey(link.getValue()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(link -> byId.get(link.getValue()), Collectors.toList())));
    }
}
