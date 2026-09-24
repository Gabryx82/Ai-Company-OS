package com.aicompany.backend.workspace.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.dto.ProjectResponse;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectType;
import com.aicompany.backend.workspace.service.ProjectTypeCatalog;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** A project's profile, folder and governed documents (ADR-020). */
@RestController
public class ProjectWorkspaceController {

    public record ProfileRequest(ProjectType projectType,
                                 @Size(max = 2000) String stack,
                                 @Size(max = 1000) String workspacePath,
                                 AutonomyLevel autonomyLevel) {
    }

    public record DocumentWrite(@NotNull @Size(max = 1_000_000) String content) {
    }

    private final ProjectWorkspaceService service;
    private final ProjectTypeCatalog types;

    public ProjectWorkspaceController(ProjectWorkspaceService service, ProjectTypeCatalog types) {
        this.service = service;
        this.types = types;
    }

    @GetMapping("/api/catalog/project-types")
    public List<ProjectTypeCatalog.ProjectTypeInfo> projectTypes() {
        return types.all();
    }

    /** The folder a project named {@code name} would get by default. */
    @GetMapping("/api/workspace/default-folder")
    public Map<String, String> defaultFolder(@RequestParam String name) {
        return Map.of("path", service.defaultFolder(name).toString());
    }

    @PutMapping("/api/projects/{id}/profile")
    public ResponseEntity<ProjectResponse> configure(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ProfileRequest request) {
        Project project = service.configure(id, new ProjectWorkspaceService.Profile(request.projectType(),
                request.stack(), request.workspacePath(), request.autonomyLevel()), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(project.getVersion())).body(ProjectResponse.from(project));
    }

    /** Creates the governed structure. Idempotent; an existing file is never overwritten (W1). */
    @PostMapping("/api/projects/{id}/workspace")
    public List<ProjectWorkspaceService.ScaffoldEntry> scaffold(@PathVariable Long id) {
        return service.scaffold(id);
    }

    @GetMapping("/api/projects/{id}/documents")
    public ProjectWorkspaceService.Documents documents(@PathVariable Long id) {
        return service.documents(id);
    }

    @GetMapping("/api/projects/{id}/files")
    public ResponseEntity<byte[]> read(@PathVariable Long id, @RequestParam String path) {
        ProjectWorkspaceService.FileContent file = service.read(id, path);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());
    }

    @PutMapping("/api/projects/{id}/files")
    public ProjectWorkspaceService.Document write(@PathVariable Long id, @RequestParam String path,
                                                  @Valid @RequestBody DocumentWrite body) {
        return service.write(id, path, body.content());
    }
}
