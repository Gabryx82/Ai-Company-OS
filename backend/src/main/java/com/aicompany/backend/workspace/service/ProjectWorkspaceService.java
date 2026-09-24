package com.aicompany.backend.workspace.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectType;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.software.service.ProjectFolders;
import com.aicompany.backend.workspace.exception.WorkspaceFileNotFoundException;
import com.aicompany.backend.workspace.exception.WorkspaceNotConfiguredException;
import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.exception.WorkspaceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The project's folder and its governed documents (ADR-020): the profile in the
 * database, the context in the files.
 */
@Service
@Transactional
public class ProjectWorkspaceService implements ProjectFolders {

    public record Profile(ProjectType projectType, String stack, String workspacePath, AutonomyLevel autonomyLevel) {
    }

    public enum Outcome { CREATED, EXISTING }

    public record ScaffoldEntry(String path, Outcome outcome) {
    }

    public record Document(String path, long size, Instant modified) {
    }

    public record Documents(String workspacePath, boolean exists, Document masterPrompt, boolean masterPromptIsTemplate,
                            Document agents, Document implementationPlan, Document planJson,
                            List<Document> phases, List<Document> tasks, List<Document> references,
                            List<Document> handoffs) {
    }

    public record FileContent(String path, String contentType, byte[] bytes) {
    }

    private static final String TEMPLATE_MARKER = "Sostituisci questo file con il MASTER PROMPT";
    private static final List<String> REFERENCE_FOLDERS = List.of("images", "mockups", "screenshots", "design-targets");

    private final ProjectRepository projects;
    private final Path root;

    public ProjectWorkspaceService(ProjectRepository projects,
                                   @Value("${aicos.workspace.root:${user.home}/AI-Company-Projects}") String root) {
        this.projects = projects;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    // --- the profile ------------------------------------------------------------

    /** The folder a new project would get: the workspace root, then a slug of its name. */
    @Transactional(readOnly = true)
    public Path defaultFolder(String projectName) {
        String slug = Normalizer.normalize(projectName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return root.resolve(slug.isEmpty() ? "project" : slug);
    }

    public Project configure(Long projectId, Profile profile, Precondition precondition) {
        String folder = profile.workspacePath() == null || profile.workspacePath().isBlank()
                ? null : profile.workspacePath().strip();
        if (folder != null) {
            try {
                if (!Path.of(folder).isAbsolute()) {
                    throw new RequestValidationException("workspacePath", "must be an absolute folder path");
                }
            } catch (InvalidPathException e) {
                throw new RequestValidationException("workspacePath", "is not a valid path");
            }
        }
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        precondition.requireSatisfiedBy(project.getVersion());
        if (folder == null) {
            folder = defaultFolder(project.getName()).toString();
        }
        project.configureProfile(profile.projectType(), blankToNull(profile.stack()), folder, profile.autonomyLevel());
        projects.flush();
        return project;
    }

    // --- the folder (ADR-019 I2) --------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<Path> folderOf(Long projectId) {
        return projects.findById(projectId)
                .map(Project::getWorkspacePath)
                .map(Path::of)
                .filter(Files::isDirectory);
    }

    /** The configured folder of a project, whether or not it exists yet. */
    @Transactional(readOnly = true)
    public Path workspaceOf(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.getWorkspacePath() == null) {
            throw new WorkspaceNotConfiguredException("Project " + projectId + " has no workspace folder: set its profile first");
        }
        return Path.of(project.getWorkspacePath());
    }

    // --- the governed structure (W1: never overwrite) ---------------------------

    @Transactional(readOnly = true)
    public List<ScaffoldEntry> scaffold(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        requireWritable(project);
        Path workspace = workspaceOf(projectId);
        Map<String, String> values = placeholders(project);
        List<ScaffoldEntry> entries = new ArrayList<>();
        try {
            for (String folder : List.of("docs/phases", "docs/adr", "tasks", ".aicos/handoffs")) {
                entries.add(directory(workspace, folder));
            }
            for (String folder : REFERENCE_FOLDERS) {
                entries.add(directory(workspace, "references/" + folder));
            }
            entries.add(file(workspace, "MASTER_PROMPT.md", template("MASTER_PROMPT.md", values)));
            entries.add(file(workspace, "AGENTS.md", template("AGENTS.md", values)));
            entries.add(file(workspace, "CLAUDE.md", template("CLAUDE.md", values)));
            entries.add(file(workspace, ".aicos/CONTEXT_MAP.md", template("CONTEXT_MAP.md", values)));
            entries.add(file(workspace, "references/README.md", template("REFERENCES.md", values)));
            entries.add(file(workspace, ".aicos/project.json", manifest(project)));
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("The folder " + workspace + " could not be prepared: " + e.getMessage());
        }
        return entries;
    }

    /** Rewrites the manifest from the database: the one generated file that follows the profile. */
    @Transactional(readOnly = true)
    public void refreshManifest(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        Path workspace = workspaceOf(projectId);
        if (!Files.isDirectory(workspace.resolve(".aicos"))) {
            return;
        }
        try {
            Files.writeString(workspace.resolve(".aicos/project.json"), manifest(project), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("The manifest could not be written: " + e.getMessage());
        }
    }

    // --- documents ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Documents documents(Long projectId) {
        Path workspace = workspaceOf(projectId);
        if (!Files.isDirectory(workspace)) {
            return new Documents(workspace.toString(), false, null, false, null, null, null,
                    List.of(), List.of(), List.of(), List.of());
        }
        Document master = document(workspace, "MASTER_PROMPT.md");
        boolean template = master != null && readTextOrEmpty(workspace.resolve("MASTER_PROMPT.md")).contains(TEMPLATE_MARKER);
        return new Documents(workspace.toString(), true, master, template,
                document(workspace, "AGENTS.md"),
                document(workspace, "docs/IMPLEMENTATION_PLAN.md"),
                document(workspace, ".aicos/plan.json"),
                list(workspace, "docs/phases", WorkspacePaths.TEXT_EXTENSIONS, 1),
                list(workspace, "tasks", WorkspacePaths.TEXT_EXTENSIONS, 1),
                list(workspace, "references", WorkspacePaths.IMAGE_EXTENSIONS, 3),
                list(workspace, ".aicos/handoffs", WorkspacePaths.TEXT_EXTENSIONS, 1));
    }

    @Transactional(readOnly = true)
    public FileContent read(Long projectId, String relative) {
        Path workspace = workspaceOf(projectId);
        Path path = WorkspacePaths.inside(workspace, relative);
        String rel = WorkspacePaths.relativeText(workspace, path);
        boolean text = WorkspacePaths.hasExtension(rel, WorkspacePaths.TEXT_EXTENSIONS);
        boolean image = rel.startsWith("references/") && WorkspacePaths.hasExtension(rel, WorkspacePaths.IMAGE_EXTENSIONS);
        if (!text && !image) {
            throw new WorkspacePathRefusedException("'" + relative + "' is neither a project document nor a reference image");
        }
        if (!Files.isRegularFile(path)) {
            throw new WorkspaceFileNotFoundException("No file '" + rel + "' in the project folder");
        }
        try {
            if (Files.size(path) > 5_000_000) {
                throw new WorkspacePathRefusedException("'" + rel + "' is larger than 5 MB");
            }
            return new FileContent(rel, contentType(rel), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + rel + "' could not be read");
        }
    }

    @Transactional(readOnly = true)
    public Document write(Long projectId, String relative, String content) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        requireWritable(project);
        Path workspace = workspaceOf(projectId);
        Path path = WorkspacePaths.writableDocument(workspace, relative);
        if (content.length() > 1_000_000) {
            throw new RequestValidationException("content", "must be at most 1,000,000 characters");
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return document(workspace, WorkspacePaths.relativeText(workspace, path));
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + relative + "' could not be written: " + e.getMessage());
        }
    }

    /** Reads a text document, or empty when it does not exist. For the orchestrator's own use. */
    @Transactional(readOnly = true)
    public Optional<String> readText(Long projectId, String relative) {
        Path workspace = workspaceOf(projectId);
        Path path = WorkspacePaths.inside(workspace, relative);
        return Files.isRegularFile(path) ? Optional.of(readTextOrEmpty(path)) : Optional.empty();
    }

    // --- helpers ------------------------------------------------------------------

    private static void requireWritable(Project project) {
        if (project.isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
    }

    private Map<String, String> placeholders(Project project) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROJECT_NAME", project.getName());
        values.put("PROJECT_ID", String.valueOf(project.getId()));
        values.put("PROJECT_TYPE", project.getProjectType() == null ? "non indicato" : project.getProjectType().name());
        values.put("STACK", project.getStack() == null || project.getStack().isBlank()
                ? "Da definire nel piano." : project.getStack());
        values.put("AUTONOMY", AutonomyPolicy.label(project.getAutonomyLevel()));
        values.put("AUTONOMY_RULES", AutonomyPolicy.rules(project.getAutonomyLevel()));
        return values;
    }

    private static String template(String name, Map<String, String> values) throws IOException {
        try (InputStream in = new ClassPathResource("workspace-templates/" + name).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> value : values.entrySet()) {
                text = text.replace("{{" + value.getKey() + "}}", value.getValue());
            }
            return text;
        }
    }

    private static String manifest(Project project) {
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode node = json.createObjectNode();
        node.put("aicosProjectId", project.getId());
        node.put("name", project.getName());
        node.put("projectType", project.getProjectType() == null ? null : project.getProjectType().name());
        node.put("stack", project.getStack());
        node.put("autonomyLevel", project.getAutonomyLevel().name());
        node.put("planStatus", project.getPlanStatus().name());
        ObjectNode context = node.putObject("context");
        context.put("masterPrompt", "MASTER_PROMPT.md");
        context.put("governance", "AGENTS.md");
        context.put("implementationPlan", "docs/IMPLEMENTATION_PLAN.md");
        context.put("plan", ".aicos/plan.json");
        context.put("phases", "docs/phases/");
        context.put("tasks", "tasks/");
        context.put("adr", "docs/adr/");
        context.put("references", "references/");
        context.put("handoffs", ".aicos/handoffs/");
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
    }

    private static ScaffoldEntry directory(Path workspace, String relative) throws IOException {
        Path path = workspace.resolve(relative);
        boolean existed = Files.isDirectory(path);
        Files.createDirectories(path);
        return new ScaffoldEntry(relative + "/", existed ? Outcome.EXISTING : Outcome.CREATED);
    }

    private static ScaffoldEntry file(Path workspace, String relative, String content) throws IOException {
        Path path = workspace.resolve(relative);
        if (Files.exists(path)) {
            return new ScaffoldEntry(relative, Outcome.EXISTING);
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new ScaffoldEntry(relative, Outcome.CREATED);
    }

    private static Document document(Path workspace, String relative) {
        Path path = workspace.resolve(relative);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return new Document(relative, Files.size(path), Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Document> list(Path workspace, String folder, List<String> extensions, int depth) {
        Path base = workspace.resolve(folder);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(base, depth)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> WorkspacePaths.relativeText(workspace, path))
                    .filter(rel -> WorkspacePaths.hasExtension(rel, extensions))
                    .sorted(Comparator.naturalOrder())
                    .map(rel -> document(workspace, rel))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String readTextOrEmpty(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String contentType(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md")) return "text/markdown;charset=UTF-8";
        return "text/plain;charset=UTF-8";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
