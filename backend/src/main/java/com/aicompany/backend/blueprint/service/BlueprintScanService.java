package com.aicompany.backend.blueprint.service;

import com.aicompany.backend.blueprint.layout.BlueprintLayoutEngine;
import com.aicompany.backend.blueprint.model.BlueprintEdge;
import com.aicompany.backend.blueprint.model.BlueprintNode;
import com.aicompany.backend.blueprint.model.BlueprintScan;
import com.aicompany.backend.blueprint.model.BlueprintVocabulary;
import com.aicompany.backend.blueprint.model.EdgeOrigin;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.NodeOrigin;
import com.aicompany.backend.blueprint.model.NodeStatus;
import com.aicompany.backend.blueprint.model.PathState;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.repository.BlueprintEdgeRepository;
import com.aicompany.backend.blueprint.repository.BlueprintNodeRepository;
import com.aicompany.backend.blueprint.repository.BlueprintScanRepository;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Component;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import com.aicompany.backend.workspace.exception.WorkspaceNotConfiguredException;
import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.exception.WorkspaceUnavailableException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a project's code into its Blueprint (ADR-033 §4): code → AST/parser →
 * components → relations → graph.
 *
 * <p>The folder is read outside any transaction -- it can take seconds, and a
 * database connection is not held while files are parsed -- and then reconciled
 * in one transaction, by key:
 * <ol>
 *   <li>a known block is refreshed in the fields the operator has not overridden;
 *       a new one is inserted and placed;</li>
 *   <li>a detected block the code no longer has becomes MISSING, never deleted --
 *       it may carry the operator's notes, tags and relations;</li>
 *   <li>detected relations are replaced: they are facts derived from the code, and
 *       a fact the code no longer states is not kept. A proposed relation the code
 *       now states becomes detected; a manual one stays the operator's;</li>
 *   <li>a manual block whose path now names a file becomes LINKED.</li>
 * </ol>
 * Nothing here writes a source file; the one file written is the summary for
 * agents, {@code .aicos/BLUEPRINT.md}.
 */
@Service
public class BlueprintScanService {

    public static final String SUMMARY_FILE = ".aicos/BLUEPRINT.md";

    private static final Logger log = LoggerFactory.getLogger(BlueprintScanService.class);

    private final BlueprintService blueprint;
    private final BlueprintNodeRepository nodes;
    private final BlueprintEdgeRepository edges;
    private final BlueprintScanRepository scans;
    private final ProjectWorkspaceService workspace;
    private final TransactionTemplate transactions;
    private final BlueprintScanner scanner = BlueprintScanner.withDefaultPlugins();

    public BlueprintScanService(BlueprintService blueprint, BlueprintNodeRepository nodes, BlueprintEdgeRepository edges,
                                BlueprintScanRepository scans, ProjectWorkspaceService workspace,
                                TransactionTemplate transactions) {
        this.blueprint = blueprint;
        this.nodes = nodes;
        this.edges = edges;
        this.scans = scans;
        this.workspace = workspace;
        this.transactions = transactions;
    }

    public BlueprintViews.BlueprintScanReport scan(Long projectId, String requestedBy) {
        transactions.executeWithoutResult(s -> blueprint.requireWritableProject(projectId));
        Path folder = workspace.folderOf(projectId).orElseThrow(() -> new WorkspaceNotConfiguredException(
                "The folder of project " + projectId + " does not exist: prepare the workspace first"));
        Instant started = Instant.now();
        BlueprintScanner.Result result;
        try {
            result = scanner.scan(folder);
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("The project folder could not be read: " + e.getMessage());
        }
        BlueprintScan scan = transactions.execute(s -> reconcile(projectId, folder, result, started, requestedBy));
        try {
            workspace.write(projectId, SUMMARY_FILE, transactions.execute(s -> summary(projectId, scan)));
        } catch (RuntimeException notWritten) {
            // The summary is context for agents; the Blueprint itself is already saved.
            log.warn("Blueprint summary of project {} not written: {}", projectId, notWritten.getMessage());
        }
        return BlueprintService.scan(scan);
    }

    private BlueprintScan reconcile(Long projectId, Path folder, BlueprintScanner.Result result, Instant started,
                                    String requestedBy) {
        blueprint.requireWritableProject(projectId);
        BlueprintScan scan = scans.saveAndFlush(new BlueprintScan(projectId, started, requestedBy));
        List<BlueprintNode> existing = nodes.findAllByProjectIdOrderByIdAsc(projectId);
        Map<String, BlueprintNode> byKey = existing.stream()
                .collect(Collectors.toMap(BlueprintNode::getKey, n -> n, (a, b) -> a, LinkedHashMap::new));

        // 1. blocks
        Set<String> seen = new HashSet<>();
        List<BlueprintNode> added = new ArrayList<>();
        for (Component c : result.components()) {
            BlueprintNode node = byKey.get(c.key());
            if (node == null) {
                node = new BlueprintNode(projectId, c.key(), c.kind(), NodeOrigin.DETECTED, NodeStatus.IMPLEMENTED,
                        PathState.LINKED, BlueprintService.cut(c.name(), 255));
                added.add(node);
                byKey.put(c.key(), node);
            } else if (!node.isDetected()) {
                continue;
            }
            apply(node, c, scan.getId());
            nodes.save(node);
            seen.add(c.key());
        }
        nodes.flush();

        // 2. what the code no longer has, and 4. manual paths that became files
        int missing = 0;
        for (BlueprintNode node : existing) {
            if (node.isDetected() && node.getParentId() == null && !seen.contains(node.getKey())) {
                if (node.getPathState() != PathState.MISSING) {
                    node.setPathState(PathState.MISSING);
                    missing++;
                }
            } else if (!node.isDetected() && node.getFilePath() != null) {
                boolean exists = exists(folder, node.getFilePath());
                if (exists && node.getPathState() != PathState.LINKED && node.getPathState() != PathState.VIRTUAL) {
                    node.setPathState(PathState.LINKED);
                } else if (!exists && node.getPathState() == PathState.LINKED) {
                    node.setPathState(PathState.MISSING);
                }
            }
        }
        for (BlueprintNode node : existing) {
            // the methods of a class that is gone are gone with it
            if (node.getParentId() != null) {
                BlueprintNode parent = existing.stream().filter(p -> p.getId().equals(node.getParentId())).findFirst().orElse(null);
                if (parent != null && parent.getPathState() == PathState.MISSING) {
                    node.setPathState(PathState.MISSING);
                }
            }
        }
        nodes.flush();

        // 3. relations
        edges.deleteAllInBatch(edges.findAllByProjectIdAndOrigin(projectId, EdgeOrigin.DETECTED));
        edges.flush();
        Map<String, Long> ids = new HashMap<>();
        nodes.findAllByProjectIdOrderByIdAsc(projectId).forEach(n -> ids.put(n.getKey(), n.getId()));
        Map<String, BlueprintEdge> kept = new HashMap<>();
        edges.findAllByProjectIdOrderByIdAsc(projectId).forEach(e -> kept.put(triple(e.getSourceNodeId(), e.getTargetNodeId(), e.getRelation()), e));
        int edgeCount = 0;
        for (BlueprintScanner.Relation r : result.relations()) {
            Long source = ids.get(r.sourceKey());
            Long target = ids.get(r.targetKey());
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            BlueprintEdge edge = kept.get(triple(source, target, r.relation()));
            if (edge != null && edge.getOrigin() == EdgeOrigin.MANUAL) {
                continue;
            }
            if (edge == null) {
                edge = new BlueprintEdge(projectId, source, target, r.relation(), EdgeOrigin.DETECTED);
                kept.put(triple(source, target, r.relation()), edge);
            }
            edge.setOrigin(EdgeOrigin.DETECTED);
            edge.setLabel(BlueprintService.cut(r.label(), 255));
            edge.setCodeReference(r.codePath(), r.line(), r.excerpt());
            edges.save(edge);
            edgeCount++;
        }
        edges.flush();

        // expanded classes stay expanded
        for (BlueprintNode node : nodes.findAllByProjectIdOrderByIdAsc(projectId)) {
            if (node.isExpanded() && node.getParentId() == null && node.getPathState() != PathState.MISSING) {
                blueprint.materializeMethods(node, ids, scan.getId());
            }
        }

        // placing the new blocks, keeping the operator's arrangement of the others
        place(projectId);

        String languages = result.languages().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + " (" + result.fidelity().getOrDefault(e.getKey(), "?") + ")")
                .collect(Collectors.joining(", "));
        scan.finish(result.files(), result.truncated(), result.components().size(), edgeCount, added.size(), missing,
                result.unresolved(), languages);
        return scans.saveAndFlush(scan);
    }

    /**
     * The code's values into a block, leaving the overridden fields alone. A block
     * the code has not changed is not written at all -- its version stays, so an
     * Inspector open on it keeps a valid tag across a scan (ADR-009). lastScanId is
     * therefore the last scan that changed the block.
     */
    private static void apply(BlueprintNode node, Component c, Long scanId) {
        List<Object> before = fingerprint(node);
        if (!node.isOverridden("name")) node.setName(BlueprintService.cut(c.name(), 255));
        if (!node.isOverridden("kind")) node.setKind(c.kind());
        if (!node.isOverridden("description")) node.setDescription(BlueprintService.cut(c.description(), 4000));
        if (!node.isOverridden("pseudocode")) node.setPseudocode(BlueprintService.cut(BlueprintService.blankToNull(c.pseudocode()), 8000));
        node.setFilePath(c.filePath());
        node.setLanguage(c.language());
        node.setStructure(BlueprintJson.write(c.structure()));
        node.setDetected(BlueprintJson.write(new BlueprintJson.Detected(BlueprintService.cut(c.name(), 255), c.kind(),
                BlueprintService.cut(c.description(), 4000),
                BlueprintService.cut(BlueprintService.blankToNull(c.pseudocode()), 8000))));
        node.setLines(c.startLine(), c.endLine());
        node.setPathState(PathState.LINKED);
        if (node.getId() == null || !before.equals(fingerprint(node))) {
            node.setLastScanId(scanId);
        }
    }

    private static List<Object> fingerprint(BlueprintNode n) {
        return java.util.Arrays.asList(n.getName(), n.getKind(), n.getDescription(), n.getPseudocode(), n.getFilePath(),
                n.getLanguage(), n.getStructure(), n.getDetected(), n.getStartLine(), n.getEndLine(), n.getPathState());
    }

    /**
     * The first scan lays everything out. Later ones keep every position the
     * operator has and place only the new blocks: in their columns, below the
     * existing arrangement, so nothing lands on top of anything.
     */
    private void place(Long projectId) {
        List<BlueprintNode> all = nodes.findAllByProjectIdOrderByIdAsc(projectId);
        Map<String, BlueprintViews.BlueprintPoint> positions = blueprint.positionsOf(projectId);
        List<BlueprintNode> unplaced = all.stream().filter(n -> !positions.containsKey(String.valueOf(n.getId()))).toList();
        if (unplaced.isEmpty()) {
            return;
        }
        Map<String, BlueprintViews.BlueprintPoint> computed = blueprint.computeLayout(all, edges.findAllByProjectIdOrderByIdAsc(projectId));
        if (positions.isEmpty()) {
            blueprint.savePositions(projectId, computed);
            return;
        }
        double below = positions.values().stream().mapToDouble(BlueprintViews.BlueprintPoint::y).max().orElse(0)
                + 2 * BlueprintLayoutEngine.ROW_HEIGHT;
        double top = unplaced.stream().map(n -> computed.get(String.valueOf(n.getId()))).mapToDouble(BlueprintViews.BlueprintPoint::y)
                .min().orElse(0);
        for (BlueprintNode n : unplaced) {
            BlueprintViews.BlueprintPoint p = computed.get(String.valueOf(n.getId()));
            positions.put(String.valueOf(n.getId()), new BlueprintViews.BlueprintPoint(p.x(), below + p.y() - top));
        }
        blueprint.savePositions(projectId, positions);
    }

    private static boolean exists(Path folder, String relative) {
        try {
            return Files.isRegularFile(WorkspacePaths.inside(folder, relative));
        } catch (WorkspacePathRefusedException refused) {
            return false;
        }
    }

    private static String triple(Long source, Long target, RelationType relation) {
        return source + ":" + target + ":" + relation;
    }

    /** {@code .aicos/BLUEPRINT.md}: the components and their relations, as context an agent reads. */
    private String summary(Long projectId, BlueprintScan scan) {
        List<BlueprintNode> all = nodes.findAllByProjectIdOrderByIdAsc(projectId).stream()
                .filter(n -> n.getPathState() != PathState.MISSING).toList();
        Map<Long, BlueprintNode> byId = all.stream().collect(Collectors.toMap(BlueprintNode::getId, n -> n));
        List<BlueprintEdge> relations = edges.findAllByProjectIdOrderByIdAsc(projectId);
        StringBuilder md = new StringBuilder("# Blueprint del progetto\n\n");
        md.append("> Generato da AI Company OS il ").append(scan.getFinishedAt())
                .append(" (scheda *Blueprint* del progetto). Componenti e relazioni rilevati dal codice, più quelli aggiunti a mano.\n\n");
        md.append("- **Componenti**: ").append(all.size()).append('\n');
        md.append("- **Relazioni**: ").append(relations.size()).append('\n');
        md.append("- **Linguaggi**: ").append(scan.getLanguages()).append("\n\n");
        Map<NodeKind, List<BlueprintNode>> byKind = all.stream().filter(n -> n.getParentId() == null)
                .collect(Collectors.groupingBy(BlueprintNode::getKind, LinkedHashMap::new, Collectors.toList()));
        int lines = 0;
        for (NodeKind kind : NodeKind.values()) {
            List<BlueprintNode> group = byKind.get(kind);
            if (group == null || kind == NodeKind.TEST) {
                continue;
            }
            md.append("## ").append(BlueprintVocabulary.of(kind).label()).append(" (").append(group.size()).append(")\n\n");
            for (BlueprintNode n : group) {
                if (++lines > 400) {
                    md.append("- …\n");
                    break;
                }
                md.append("- **").append(n.getName()).append("**");
                if (n.getFilePath() != null) md.append(" — `").append(n.getFilePath()).append('`');
                List<String> out = relations.stream().filter(e -> e.getSourceNodeId().equals(n.getId()))
                        .filter(e -> byId.containsKey(e.getTargetNodeId()))
                        .map(e -> BlueprintVocabulary.of(e.getRelation()).label() + " " + byId.get(e.getTargetNodeId()).getName())
                        .distinct().limit(8).toList();
                if (!out.isEmpty()) md.append(" — ").append(String.join("; ", out));
                md.append('\n');
            }
            md.append('\n');
        }
        long tests = all.stream().filter(n -> n.getKind() == NodeKind.TEST).count();
        md.append("## Test\n\n").append(tests).append(" test rilevati; ogni componente elenca i propri in *Related tests* nella console.\n");
        return md.toString();
    }
}
