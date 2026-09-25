package com.aicompany.backend.graph.code;

import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.workspace.exception.WorkspaceNotConfiguredException;
import com.aicompany.backend.workspace.exception.WorkspaceUnavailableException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graph engineering for a project (ADR-030): scans its folder, links the tasks
 * whose documents name files, and writes the result where agents read context --
 * {@code .aicos/code-graph.json} for tools, {@code .aicos/CODE_GRAPH.md} for people
 * and models.
 */
@Service
@Transactional(readOnly = true)
public class CodeGraphService {

    public static final String JSON_FILE = ".aicos/code-graph.json";
    public static final String MARKDOWN_FILE = ".aicos/CODE_GRAPH.md";

    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\s]+\\.[A-Za-z0-9]+)`");

    public record Hotspot(String id, int importedBy) {
    }

    public record TaskLink(Long taskId, String code, String title, String file) {
    }

    public record CodeGraph(Instant generatedAt, int files, boolean truncated, Map<String, Integer> languages,
                            List<CodeGraphScanner.Node> nodes, List<CodeGraphScanner.Edge> edges,
                            List<List<String>> cycles, List<Hotspot> mostImported, int externals,
                            List<TaskLink> taskLinks) {
    }

    private final ProjectWorkspaceService workspace;
    private final TaskRepository tasks;
    private final JsonMapper json = JsonMapper.builder().build();

    public CodeGraphService(ProjectWorkspaceService workspace, TaskRepository tasks) {
        this.workspace = workspace;
        this.tasks = tasks;
    }

    /** Scans the folder now and writes the two files. */
    public CodeGraph scan(Long projectId) {
        Path folder = workspace.folderOf(projectId).orElseThrow(() -> new WorkspaceNotConfiguredException(
                "The folder of project " + projectId + " does not exist: prepare the workspace first"));
        CodeGraphScanner.Graph graph;
        try {
            graph = CodeGraphScanner.scan(folder);
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("The project folder could not be read: " + e.getMessage());
        }
        Set<String> files = new HashSet<>();
        graph.nodes().forEach(n -> files.add(n.id()));
        List<TaskLink> links = new ArrayList<>();
        for (Task task : tasks.findAllByProjectId(projectId)) {
            if (task.getDocumentPath() == null) {
                continue;
            }
            workspace.readText(projectId, task.getDocumentPath()).ifPresent(text -> {
                Matcher m = BACKTICKED.matcher(text);
                Set<String> seen = new HashSet<>();
                while (m.find()) {
                    String path = m.group(1).replace('\\', '/').replaceFirst("^\\./", "");
                    if (files.contains(path) && seen.add(path)) {
                        links.add(new TaskLink(task.getId(), task.getCode(), task.getTitle(), path));
                    }
                }
            });
        }
        Map<String, Integer> inDegree = new HashMap<>();
        graph.edges().stream().filter(e -> e.kind().equals("IMPORT")).forEach(e -> inDegree.merge(e.target(), 1, Integer::sum));
        List<Hotspot> hotspots = inDegree.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10).map(e -> new Hotspot(e.getKey(), e.getValue())).toList();
        int externals = (int) graph.nodes().stream().filter(n -> n.kind().equals("EXTERNAL")).count();

        CodeGraph result = new CodeGraph(Instant.now(), graph.files(), graph.truncated(), graph.languages(),
                graph.nodes(), graph.edges(), graph.cycles(), hotspots, externals, links);
        workspace.write(projectId, JSON_FILE, json.writeValueAsString(result));
        workspace.write(projectId, MARKDOWN_FILE, markdown(result));
        return result;
    }

    /** The last scan, from the project folder; empty when there is none. */
    public Optional<CodeGraph> last(Long projectId) {
        try {
            return workspace.readText(projectId, JSON_FILE).map(text -> json.readValue(text, CodeGraph.class));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    static String markdown(CodeGraph g) {
        StringBuilder md = new StringBuilder("# Grafo del codice\n\n");
        md.append("> Generato da AI Company OS il ").append(g.generatedAt()).append(". Rigeneralo dalla scheda *Codice* del progetto.\n")
                .append("> Forma completa per gli strumenti: `.aicos/code-graph.json`.\n\n");
        md.append("- **File sorgente**: ").append(g.files()).append(g.truncated() ? " (troncato)" : "").append('\n');
        g.languages().forEach((lang, n) -> md.append("  - ").append(lang).append(": ").append(n).append('\n'));
        md.append("- **Import interni**: ").append(g.edges().stream().filter(e -> e.kind().equals("IMPORT")).count()).append('\n');
        md.append("- **Pacchetti esterni**: ").append(g.externals()).append('\n');
        md.append("- **Cicli di import**: ").append(g.cycles().size()).append("\n\n");
        if (!g.mostImported().isEmpty()) {
            md.append("## I file più importati\n\n");
            g.mostImported().forEach(h -> md.append("- `").append(h.id()).append("` — importato da ").append(h.importedBy()).append('\n'));
            md.append('\n');
        }
        if (!g.cycles().isEmpty()) {
            md.append("## Cicli di import (da spezzare)\n\n");
            g.cycles().forEach(c -> md.append("- ").append(String.join(" ↔ ", c.stream().map(f -> "`" + f + "`").toList())).append('\n'));
            md.append('\n');
        }
        List<String> externals = g.nodes().stream().filter(n -> n.kind().equals("EXTERNAL"))
                .sorted(Comparator.comparing(CodeGraphScanner.Node::language).thenComparing(CodeGraphScanner.Node::label))
                .map(n -> n.label() + " (" + n.language() + ")").toList();
        if (!externals.isEmpty()) {
            md.append("## Dipendenze esterne\n\n").append(String.join(", ", externals)).append("\n\n");
        }
        if (!g.taskLinks().isEmpty()) {
            md.append("## Task e file\n\n");
            g.taskLinks().forEach(l -> md.append("- ").append(l.code() == null ? "#" + l.taskId() : l.code()).append(" ")
                    .append(l.title()).append(" → `").append(l.file()).append("`\n"));
        }
        return md.toString();
    }
}
