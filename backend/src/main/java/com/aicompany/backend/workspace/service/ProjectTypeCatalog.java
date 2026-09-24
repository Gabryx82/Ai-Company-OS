package com.aicompany.backend.workspace.service;

import com.aicompany.backend.project.model.ProjectType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * The project types and what the Master Orchestrator proposes for each one:
 * stack, catalog software, agent roles (ADR-020 §3). Read-only reference data,
 * loaded once from {@code classpath:catalog/project-types.json}.
 */
@Component
public class ProjectTypeCatalog {

    public record ProjectTypeInfo(ProjectType type, String label, String description, List<String> stack,
                                  List<String> software, List<String> agentRoles) {
    }

    private final List<ProjectTypeInfo> types;

    public ProjectTypeCatalog() {
        JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = new ClassPathResource("catalog/project-types.json").getInputStream()) {
            this.types = List.of(json.readValue(in, ProjectTypeInfo[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("catalog/project-types.json is unreadable", e);
        }
    }

    public List<ProjectTypeInfo> all() {
        return types;
    }

    public Optional<ProjectTypeInfo> of(ProjectType type) {
        return types.stream().filter(info -> info.type() == type).findFirst();
    }
}
