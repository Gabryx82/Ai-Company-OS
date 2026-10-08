package com.aicompany.backend.blueprint.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.blueprint.exception.BlueprintEdgeNotFoundException;
import com.aicompany.backend.blueprint.exception.BlueprintNodeNotFoundException;
import com.aicompany.backend.blueprint.exception.DerivedElementException;
import com.aicompany.backend.blueprint.exception.RelationExistsException;
import com.aicompany.backend.blueprint.layout.BlueprintLayoutEngine;
import com.aicompany.backend.blueprint.model.BlueprintEdge;
import com.aicompany.backend.blueprint.model.BlueprintLayout;
import com.aicompany.backend.blueprint.model.BlueprintNode;
import com.aicompany.backend.blueprint.model.BlueprintScan;
import com.aicompany.backend.blueprint.model.BlueprintVocabulary;
import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.EdgeOrigin;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.NodeOrigin;
import com.aicompany.backend.blueprint.model.NodeStatus;
import com.aicompany.backend.blueprint.model.PathState;
import com.aicompany.backend.blueprint.model.ProposalKind;
import com.aicompany.backend.blueprint.model.ProposalStatus;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.repository.BlueprintEdgeRepository;
import com.aicompany.backend.blueprint.repository.BlueprintLayoutRepository;
import com.aicompany.backend.blueprint.repository.BlueprintNodeRepository;
import com.aicompany.backend.blueprint.repository.BlueprintScanRepository;
import com.aicompany.backend.blueprint.repository.ChangeProposalRepository;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Project Blueprint (ADR-033): its blocks, relations and layout, as the
 * operator reads and edits them. Writes only its own tables -- never a file of
 * the project (§7). Reading the code into it is {@link BlueprintScanService}'s.
 *
 * <p>Every mutation of an existing row takes a {@link Precondition} (ADR-009);
 * the layout has its own version so a drag never invalidates an edit (§6). A
 * project that is archived is read, never written (ADR-004 §8).
 */
@Service
@Transactional
public class BlueprintService {

    /** What a client sends for a block: every field, PUT semantics. */
    public record NodeDraft(NodeKind kind, String name, String filePath, String language, String description,
                            String pseudocode, String notes, List<String> tags, NodeStatus status, PathState pathState,
                            CodeStructure structure, Double x, Double y) {
    }

    public record EdgeDraft(Long sourceId, Long targetId, RelationType relation, EdgeOrigin origin, String label,
                            String description) {
    }

    static final int MAX_TAGS = 20;
    static final int SNIPPET_LINES = 120;
    static final int FILE_LINES = 3000;

    private final BlueprintNodeRepository nodes;
    private final BlueprintEdgeRepository edges;
    private final BlueprintLayoutRepository layouts;
    private final BlueprintScanRepository scans;
    private final ChangeProposalRepository proposals;
    private final ProjectRepository projects;
    private final ProjectWorkspaceService workspace;

    public BlueprintService(BlueprintNodeRepository nodes, BlueprintEdgeRepository edges, BlueprintLayoutRepository layouts,
                            BlueprintScanRepository scans, ChangeProposalRepository proposals, ProjectRepository projects,
                            ProjectWorkspaceService workspace) {
        this.nodes = nodes;
        this.edges = edges;
        this.layouts = layouts;
        this.scans = scans;
        this.proposals = proposals;
        this.projects = projects;
        this.workspace = workspace;
    }

    // --- reading --------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BlueprintViews.BlueprintVocabularyView vocabulary() {
        return new BlueprintViews.BlueprintVocabularyView(BlueprintVocabulary.kinds(), BlueprintVocabulary.relations(),
                BlueprintVocabulary.actions(), List.of(NodeStatus.values()), List.of(PathState.values()),
                List.of(ProposalKind.values()));
    }

    @Transactional(readOnly = true)
    public BlueprintViews.BlueprintGraph graph(Long projectId) {
        return graph(projectId, GraphQuery.WHOLE);
    }

    /**
     * PHASE 32 (TD-61): what the canvas asks for, progressively. {@code outline}
     * leaves out the texts -- block descriptions and pseudocode, relation labels,
     * descriptions and code references -- which the canvas shows only from a
     * closer zoom; {@code within} keeps the blocks whose position falls in a
     * rectangle of the canvas, and the relations between them. Positions, metrics
     * and counts always describe the whole Blueprint.
     */
    public record GraphQuery(boolean outline, double @Nullable [] within) {
        public static final GraphQuery WHOLE = new GraphQuery(false, null);
    }

    /** The size of a block on the canvas, so that one partly inside a rectangle counts as inside. */
    static final double BLOCK_WIDTH = 260;
    static final double BLOCK_HEIGHT = 160;

    @Transactional(readOnly = true)
    public BlueprintViews.BlueprintGraph graph(Long projectId, GraphQuery query) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        List<BlueprintNode> all = nodes.findAllByProjectIdOrderByIdAsc(projectId);
        List<BlueprintEdge> relations = edges.findAllByProjectIdOrderByIdAsc(projectId);
        List<BlueprintViews.BlueprintBlock> summaries = all.stream().map(BlueprintService::summary).toList();
        List<BlueprintViews.BlueprintRelation> edgeViews = relations.stream().map(BlueprintService::edge).toList();
        Optional<BlueprintScan> last = scans.findFirstByProjectIdOrderByIdDesc(projectId);
        int pending = (int) proposals.findAllByProjectIdOrderByIdDesc(projectId).stream()
                .filter(p -> p.getStatus() == ProposalStatus.PROPOSED).count();
        BlueprintViews.BlueprintCanvasLayout layout = layoutOf(projectId, all);
        BlueprintViews.BlueprintMetrics metrics = metrics(summaries, edgeViews, last.map(BlueprintScan::getUnresolved).orElse(0));

        List<BlueprintViews.BlueprintBlock> blocks = summaries;
        List<BlueprintViews.BlueprintRelation> lines = edgeViews;
        if (query.within() != null) {
            double[] box = query.within();
            blocks = blocks.stream().filter(b -> {
                BlueprintViews.BlueprintPoint p = layout.positions().get(String.valueOf(b.id()));
                return p != null && p.x() + BLOCK_WIDTH >= box[0] && p.x() <= box[2]
                        && p.y() + BLOCK_HEIGHT >= box[1] && p.y() <= box[3];
            }).toList();
            java.util.Set<Long> inside = blocks.stream().map(BlueprintViews.BlueprintBlock::id)
                    .collect(Collectors.toSet());
            lines = lines.stream().filter(e -> inside.contains(e.source()) && inside.contains(e.target())).toList();
        }
        if (query.outline()) {
            blocks = blocks.stream().map(b -> new BlueprintViews.BlueprintBlock(b.id(), b.key(), b.kind(), b.layer(),
                    b.family(), b.origin(), b.status(), b.pathState(), b.name(), b.filePath(), b.language(), null, null,
                    b.tags(), b.parentId(), b.expanded(), b.expandable(), b.members(), b.version())).toList();
            lines = lines.stream().map(e -> new BlueprintViews.BlueprintRelation(e.id(), e.source(), e.target(),
                    e.relation(), e.family(), e.origin(), null, null, null, e.version())).toList();
        }
        return new BlueprintViews.BlueprintGraph(projectId, project.isArchived(), workspace.folderOf(projectId).isPresent(),
                blocks, lines, layout, last.map(BlueprintService::scan).orElse(null), metrics, pending,
                query.outline(), query.within() != null);
    }

    @Transactional(readOnly = true)
    public BlueprintViews.BlueprintBlockDetail node(Long projectId, Long nodeId) {
        requireProject(projectId);
        return detail(requireNode(projectId, nodeId));
    }

    @Transactional(readOnly = true)
    public List<BlueprintViews.BlueprintScanReport> scans(Long projectId) {
        requireProject(projectId);
        return scans.findTop10ByProjectIdOrderByIdDesc(projectId).stream().map(BlueprintService::scan).toList();
    }

    /**
     * The code of a block, read from its file: the lines of the component, or
     * the head of the file when the lines are unknown; with {@code full}, the whole
     * file up to {@link #FILE_LINES} lines. Only files inside the project folder.
     */
    @Transactional(readOnly = true)
    public BlueprintViews.BlueprintSnippet snippet(Long projectId, Long nodeId, boolean full) {
        requireProject(projectId);
        BlueprintNode node = requireNode(projectId, nodeId);
        if (node.getFilePath() == null || node.getFilePath().isBlank()) {
            throw new RequestValidationException("filePath", "this block has no file");
        }
        Path folder = workspace.folderOf(projectId).orElseThrow(() -> new RequestValidationException("filePath",
                "the project folder is not available"));
        Path file = WorkspacePaths.inside(folder, node.getFilePath());
        if (!Files.isRegularFile(file)) {
            throw new RequestValidationException("filePath", "'" + node.getFilePath() + "' does not exist in the project folder");
        }
        List<String> lines;
        try {
            if (Files.size(file) > 2_000_000) {
                throw new WorkspacePathRefusedException("'" + node.getFilePath() + "' is larger than 2 MB");
            }
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RequestValidationException("filePath", "'" + node.getFilePath() + "' could not be read as text");
        }
        int start;
        int end;
        if (full || node.getStartLine() == null) {
            start = 1;
            end = Math.min(lines.size(), full ? FILE_LINES : SNIPPET_LINES);
        } else {
            start = Math.max(1, node.getStartLine());
            int wanted = node.getEndLine() == null ? start + SNIPPET_LINES - 1 : node.getEndLine();
            end = Math.min(lines.size(), Math.min(wanted, start + SNIPPET_LINES - 1));
        }
        boolean truncated = full ? lines.size() > FILE_LINES
                : node.getEndLine() != null ? node.getEndLine() > end : lines.size() > end;
        String text = start > end ? "" : String.join("\n", lines.subList(start - 1, end));
        return new BlueprintViews.BlueprintSnippet(node.getFilePath(), node.getLanguage(), start, end, text, truncated);
    }

    // --- blocks ---------------------------------------------------------------------

    /**
     * A manual block. A path that names a real file of the project is LINKED to it;
     * a path that does not is what the operator says it is -- a file to create, a
     * reference to a document, or nothing real -- and never a file this call creates.
     */
    public BlueprintViews.BlueprintBlockDetail createNode(Long projectId, NodeDraft draft) {
        requireWritableProject(projectId);
        validate(draft);
        BlueprintNode node = new BlueprintNode(projectId, "manual:" + UUID.randomUUID(), draft.kind(), NodeOrigin.MANUAL,
                draft.status() == null ? NodeStatus.PLANNED : draft.status(), PathState.VIRTUAL, draft.name().strip());
        node.setLanguage(blankToNull(draft.language()));
        applyPath(projectId, node, draft);
        node.setDescription(blankToNull(draft.description()));
        node.setPseudocode(blankToNull(draft.pseudocode()));
        node.setNotes(blankToNull(draft.notes()));
        node.setTags(BlueprintJson.write(tags(draft.tags())));
        node.setStructure(BlueprintJson.write(draft.structure() == null ? CodeStructure.empty() : draft.structure().normalized()));
        nodes.saveAndFlush(node);
        placeNew(projectId, node, draft.x(), draft.y());
        return detail(node);
    }

    /**
     * Edits a block. On a detected block the path, language and structure are the
     * code's and cannot change here; name, kind, description and pseudocode can,
     * and each one changed becomes an override the next scan respects -- typing
     * back the detected value removes the override again.
     */
    public BlueprintViews.BlueprintBlockDetail updateNode(Long projectId, Long nodeId, NodeDraft draft, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        validate(draft);
        node.setStatus(draft.status() == null ? node.getStatus() : draft.status());
        node.setNotes(blankToNull(draft.notes()));
        node.setTags(BlueprintJson.write(tags(draft.tags())));
        if (node.isDetected()) {
            if (draft.filePath() != null && !Objects.equals(normalizePath(draft.filePath()), node.getFilePath())) {
                throw new DerivedElementException("The path of a detected block is the code's: '" + node.getFilePath() + "'");
            }
            BlueprintJson.Detected detected = BlueprintJson.detected(node.getDetected());
            Set<String> overrides = node.overriddenFields();
            override(overrides, "name", draft.name().strip(), detected == null ? node.getName() : detected.name(), node::setName);
            override(overrides, "kind", draft.kind(), detected == null ? node.getKind() : detected.kind(), node::setKind);
            override(overrides, "description", blankToNull(draft.description()),
                    detected == null ? node.getDescription() : detected.description(), node::setDescription);
            override(overrides, "pseudocode", blankToNull(draft.pseudocode()),
                    detected == null ? node.getPseudocode() : detected.pseudocode(), node::setPseudocode);
            node.setOverriddenFields(overrides);
        } else {
            node.setName(draft.name().strip());
            node.setKind(draft.kind());
            node.setLanguage(blankToNull(draft.language()));
            node.setDescription(blankToNull(draft.description()));
            node.setPseudocode(blankToNull(draft.pseudocode()));
            node.setStructure(BlueprintJson.write(draft.structure() == null ? CodeStructure.empty() : draft.structure().normalized()));
            applyPath(projectId, node, draft);
        }
        nodes.flush();
        return detail(node);
    }

    /** Takes back overrides: the named fields return to what the code says. */
    public BlueprintViews.BlueprintBlockDetail revertNode(Long projectId, Long nodeId, List<String> fields, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        if (!node.isDetected()) {
            throw new RequestValidationException("fields", "a manual block has no detected values to return to");
        }
        BlueprintJson.Detected detected = BlueprintJson.detected(node.getDetected());
        Set<String> overrides = node.overriddenFields();
        for (String field : fields == null || fields.isEmpty() ? List.copyOf(overrides) : fields) {
            if (!BlueprintNode.OVERRIDABLE.contains(field)) {
                throw new RequestValidationException("fields", "'" + field + "' is not a field that can be overridden");
            }
            if (overrides.remove(field) && detected != null) {
                switch (field) {
                    case "name" -> node.setName(detected.name());
                    case "kind" -> node.setKind(detected.kind());
                    case "description" -> node.setDescription(detected.description());
                    default -> node.setPseudocode(detected.pseudocode());
                }
            }
        }
        node.setOverriddenFields(overrides);
        nodes.flush();
        return detail(node);
    }

    /**
     * Generate/update pseudocode (directive §10). A detected block returns to the
     * pseudocode of its code; a manual block linked to a file gets the pseudocode
     * of that file's component; any other manual block gets a skeleton from its
     * structure. Deterministic and local: asking an agent is an AI action.
     */
    public BlueprintViews.BlueprintBlockDetail regeneratePseudocode(Long projectId, Long nodeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        if (node.isDetected()) {
            BlueprintJson.Detected detected = BlueprintJson.detected(node.getDetected());
            Set<String> overrides = node.overriddenFields();
            overrides.remove("pseudocode");
            node.setOverriddenFields(overrides);
            node.setPseudocode(detected == null ? node.getPseudocode() : detected.pseudocode());
        } else {
            node.setPseudocode(fromLinkedFile(projectId, node).orElseGet(() -> skeleton(node)));
        }
        nodes.flush();
        return detail(node);
    }

    /**
     * Deletes a block and, with it, its relations. What the code asserts is not
     * deleted -- it would return at the next scan: a detected block goes only when
     * the code no longer has it (MISSING).
     */
    public void deleteNode(Long projectId, Long nodeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        if (node.isDetected() && node.getPathState() != PathState.MISSING) {
            throw new DerivedElementException("'" + node.getName() + "' is in the code: remove it there, or keep it");
        }
        nodes.delete(node);
        nodes.flush();
    }

    /**
     * Progressive expansion (directive §18): the methods of a class become
     * FUNCTION blocks, contained by the class and calling what the method calls.
     * A scan keeps expanded classes expanded.
     */
    public BlueprintViews.BlueprintBlockDetail expandNode(Long projectId, Long nodeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        if (BlueprintJson.structure(node.getStructure()).methods().isEmpty()) {
            throw new RequestValidationException("expanded", "this block has no methods to expand");
        }
        node.setExpanded(true);
        nodes.flush();
        Map<String, Long> ids = nodes.findAllByProjectIdOrderByIdAsc(projectId).stream()
                .collect(Collectors.toMap(BlueprintNode::getKey, BlueprintNode::getId, (a, b) -> a));
        materializeMethods(node, ids, null);
        return detail(node);
    }

    public BlueprintViews.BlueprintBlockDetail collapseNode(Long projectId, Long nodeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintNode node = nodes.findByIdForUpdate(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
        precondition.requireSatisfiedBy(node.getVersion());
        node.setExpanded(false);
        nodes.deleteAll(nodes.findAllByParentId(node.getId()));
        nodes.flush();
        return detail(node);
    }

    /**
     * The FUNCTION blocks of an expanded class, created or refreshed from its
     * structure: a CONTAINS edge from the class, a CALLS edge to each block the
     * method calls. Method blocks the structure no longer lists are removed. Also
     * called by the scan, with the scan's id.
     */
    void materializeMethods(BlueprintNode parent, Map<String, Long> idsByKey, Long scanId) {
        CodeStructure structure = BlueprintJson.structure(parent.getStructure());
        Map<String, BlueprintNode> existing = nodes.findAllByParentId(parent.getId()).stream()
                .collect(Collectors.toMap(BlueprintNode::getKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, BlueprintViews.BlueprintPoint> positions = positionsOf(parent.getProjectId());
        BlueprintViews.BlueprintPoint anchor = positions.getOrDefault(String.valueOf(parent.getId()), new BlueprintViews.BlueprintPoint(0, 0));
        Set<String> kept = new LinkedHashSet<>();
        int row = 0;
        boolean placed = false;
        for (CodeStructure.CodeMethod m : structure.methods()) {
            String key = parent.getKey() + "#" + m.name() + "(" + String.join(",", m.parameters().stream()
                    .map(p -> p.contains(" ") ? p.substring(0, p.lastIndexOf(' ')) : p).toList()) + ")";
            if (!kept.add(key)) {
                continue;
            }
            BlueprintNode child = existing.get(key);
            if (child == null) {
                child = new BlueprintNode(parent.getProjectId(), key, NodeKind.FUNCTION, parent.getOrigin(), parent.getStatus(),
                        parent.getPathState(), m.name() + "()");
                child.setParentId(parent.getId());
            }
            if (!child.isOverridden("name")) child.setName(m.name() + "()");
            child.setFilePath(parent.getFilePath());
            child.setLanguage(parent.getLanguage());
            child.setPathState(parent.getPathState());
            child.setLines(m.startLine(), m.endLine());
            String description = m.signature() == null ? m.name() : m.signature();
            if (!child.isOverridden("description")) child.setDescription(description);
            if (!child.isOverridden("pseudocode")) child.setPseudocode(blankToNull(m.pseudocode()));
            child.setDetected(BlueprintJson.write(new BlueprintJson.Detected(m.name() + "()", NodeKind.FUNCTION, description,
                    blankToNull(m.pseudocode()))));
            child.setStructure(BlueprintJson.write(CodeStructure.builder().className(structure.className())
                    .packageName(structure.packageName()).signature(m.signature()).inputs(m.parameters())
                    .output(m.returnType()).visibility(m.visibility()).annotations(m.annotations())
                    .exceptions(m.exceptions()).calls(m.calls()).build()));
            if (scanId != null) child.setLastScanId(scanId);
            boolean isNew = child.getId() == null;
            nodes.save(child);
            if (isNew) {
                nodes.flush();
                positions.put(String.valueOf(child.getId()), new BlueprintViews.BlueprintPoint(anchor.x() + 60,
                        anchor.y() + 150 + row * 90));
                placed = true;
            }
            row++;
            idsByKey.put(key, child.getId());
            upsertDetectedEdge(parent.getProjectId(), parent.getId(), child.getId(), RelationType.CONTAINS, m.name() + "()",
                    parent.getFilePath(), m.startLine(), m.signature());
            for (String group : m.callTargets()) {
                Arrays.stream(group.split("\\|")).map(idsByKey::get).filter(Objects::nonNull).findFirst()
                        .filter(target -> !target.equals(parent.getId()))
                        .ifPresent(target -> upsertDetectedEdge(parent.getProjectId(), childId(idsByKey, key), target,
                                RelationType.CALLS, String.join(", ", m.calls()), parent.getFilePath(), m.startLine(), null));
            }
        }
        existing.forEach((key, child) -> {
            if (!kept.contains(key)) {
                nodes.delete(child);
            }
        });
        nodes.flush();
        if (placed) {
            savePositions(parent.getProjectId(), positions);
        }
    }

    private static Long childId(Map<String, Long> ids, String key) {
        return ids.get(key);
    }

    private void upsertDetectedEdge(Long projectId, Long source, Long target, RelationType relation, String label,
                                    String path, Integer line, String excerpt) {
        if (source.equals(target)) {
            return;
        }
        BlueprintEdge edge = edges.findBySourceNodeIdAndTargetNodeIdAndRelation(source, target, relation)
                .orElseGet(() -> new BlueprintEdge(projectId, source, target, relation, EdgeOrigin.DETECTED));
        if (edge.getOrigin() == EdgeOrigin.MANUAL) {
            return;
        }
        edge.setOrigin(EdgeOrigin.DETECTED);
        edge.setLabel(cut(label, 255));
        edge.setCodeReference(path, line, excerpt);
        edges.save(edge);
    }

    // --- relations ------------------------------------------------------------------

    /** A relation drawn by the operator (MANUAL) or suggested (PROPOSED); never a DETECTED one. */
    public BlueprintViews.BlueprintRelation createEdge(Long projectId, EdgeDraft draft) {
        requireWritableProject(projectId);
        if (draft.sourceId() == null || draft.targetId() == null) {
            throw new RequestValidationException("sourceId", "a relation needs a source and a target block");
        }
        if (draft.relation() == null) {
            throw new RequestValidationException("relation", "is required");
        }
        EdgeOrigin origin = draft.origin() == null ? EdgeOrigin.MANUAL : draft.origin();
        if (origin == EdgeOrigin.DETECTED) {
            throw new RequestValidationException("origin", "a detected relation comes from a scan, not from a client");
        }
        if (draft.sourceId().equals(draft.targetId())) {
            throw new RequestValidationException("targetId", "a block cannot relate to itself");
        }
        requireNode(projectId, draft.sourceId());
        requireNode(projectId, draft.targetId());
        if (edges.findBySourceNodeIdAndTargetNodeIdAndRelation(draft.sourceId(), draft.targetId(), draft.relation()).isPresent()) {
            throw new RelationExistsException("There is already a " + draft.relation() + " relation between these blocks");
        }
        BlueprintEdge edge = new BlueprintEdge(projectId, draft.sourceId(), draft.targetId(), draft.relation(), origin);
        edge.setLabel(cut(blankToNull(draft.label()), 255));
        edge.setDescription(cut(blankToNull(draft.description()), 2000));
        return edge(edges.saveAndFlush(edge));
    }

    /** Label and description of any relation; the type only of one the code does not assert. */
    public BlueprintViews.BlueprintRelation updateEdge(Long projectId, Long edgeId, EdgeDraft draft, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintEdge edge = edges.findByIdForUpdate(edgeId, projectId)
                .orElseThrow(() -> new BlueprintEdgeNotFoundException(projectId, edgeId));
        precondition.requireSatisfiedBy(edge.getVersion());
        if (draft.relation() != null && draft.relation() != edge.getRelation()) {
            if (edge.getOrigin() == EdgeOrigin.DETECTED) {
                throw new DerivedElementException("The type of a detected relation is the code's");
            }
            if (edges.findBySourceNodeIdAndTargetNodeIdAndRelation(edge.getSourceNodeId(), edge.getTargetNodeId(),
                    draft.relation()).isPresent()) {
                throw new RelationExistsException("There is already a " + draft.relation() + " relation between these blocks");
            }
            edge.setRelation(draft.relation());
        }
        edge.setLabel(cut(blankToNull(draft.label()), 255));
        edge.setDescription(cut(blankToNull(draft.description()), 2000));
        edges.flush();
        return edge(edge);
    }

    public void deleteEdge(Long projectId, Long edgeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintEdge edge = edges.findByIdForUpdate(edgeId, projectId)
                .orElseThrow(() -> new BlueprintEdgeNotFoundException(projectId, edgeId));
        precondition.requireSatisfiedBy(edge.getVersion());
        if (edge.getOrigin() == EdgeOrigin.DETECTED) {
            throw new DerivedElementException("This relation is in the code: change the code to remove it");
        }
        edges.delete(edge);
        edges.flush();
    }

    /** A proposed relation, confirmed by a person: it becomes the operator's. */
    public BlueprintViews.BlueprintRelation confirmEdge(Long projectId, Long edgeId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintEdge edge = edges.findByIdForUpdate(edgeId, projectId)
                .orElseThrow(() -> new BlueprintEdgeNotFoundException(projectId, edgeId));
        precondition.requireSatisfiedBy(edge.getVersion());
        if (edge.getOrigin() != EdgeOrigin.PROPOSED) {
            throw new RequestValidationException("origin", "only a proposed relation can be confirmed");
        }
        edge.setOrigin(EdgeOrigin.MANUAL);
        edges.flush();
        return edge(edge);
    }

    // --- layout ---------------------------------------------------------------------

    /** Moves blocks: the positions sent are merged into the layout; ids of other projects are ignored. */
    public BlueprintViews.BlueprintCanvasLayout saveLayout(Long projectId, Map<String, BlueprintViews.BlueprintPoint> moved, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintLayout layout = layouts.findForUpdate(projectId).orElse(null);
        precondition.requireSatisfiedBy(layout == null ? 0 : layout.getVersion());
        Set<String> own = nodes.findAllByProjectIdOrderByIdAsc(projectId).stream().map(n -> String.valueOf(n.getId()))
                .collect(Collectors.toSet());
        Map<String, BlueprintViews.BlueprintPoint> positions = layout == null ? new LinkedHashMap<>()
                : BlueprintJson.positions(layout.getPositions());
        moved.forEach((id, p) -> {
            if (own.contains(id) && p != null && Double.isFinite(p.x()) && Double.isFinite(p.y())) {
                positions.put(id, new BlueprintViews.BlueprintPoint(Math.round(p.x()), Math.round(p.y())));
            }
        });
        return layoutView(savePositions(projectId, positions));
    }

    /** Reset layout: every block where the server's layout puts it (ADR-033 §6). */
    public BlueprintViews.BlueprintCanvasLayout autoLayout(Long projectId, Precondition precondition) {
        requireWritableProject(projectId);
        BlueprintLayout layout = layouts.findForUpdate(projectId).orElse(null);
        precondition.requireSatisfiedBy(layout == null ? 0 : layout.getVersion());
        List<BlueprintNode> all = nodes.findAllByProjectIdOrderByIdAsc(projectId);
        return layoutView(savePositions(projectId, computeLayout(all, edges.findAllByProjectIdOrderByIdAsc(projectId))));
    }

    Map<String, BlueprintViews.BlueprintPoint> computeLayout(List<BlueprintNode> all, List<BlueprintEdge> relations) {
        List<BlueprintLayoutEngine.Item> items = all.stream().map(n -> {
            BlueprintVocabulary.Layer layer = BlueprintVocabulary.layerOf(n.getKind(), n.getLanguage(), n.getFilePath());
            String group = n.getFilePath() == null ? "~" + n.getName()
                    : n.getFilePath().contains("/") ? n.getFilePath().substring(0, n.getFilePath().lastIndexOf('/')) + "/" + n.getName()
                    : n.getName();
            return new BlueprintLayoutEngine.Item(n.getId(), BlueprintVocabulary.columnOf(n.getKind(), layer), group);
        }).toList();
        List<long[]> pairs = relations.stream().map(e -> new long[]{e.getSourceNodeId(), e.getTargetNodeId()}).toList();
        Map<String, BlueprintViews.BlueprintPoint> out = new LinkedHashMap<>();
        BlueprintLayoutEngine.layout(items, pairs).forEach((id, p) ->
                out.put(String.valueOf(id), new BlueprintViews.BlueprintPoint(p.x(), p.y())));
        return out;
    }

    Map<String, BlueprintViews.BlueprintPoint> positionsOf(Long projectId) {
        return layouts.findById(projectId).map(l -> BlueprintJson.positions(l.getPositions())).orElseGet(LinkedHashMap::new);
    }

    /** Server bookkeeping of positions (new blocks, a scan, a reset): the caller decided on the precondition. */
    BlueprintLayout savePositions(Long projectId, Map<String, BlueprintViews.BlueprintPoint> positions) {
        BlueprintLayout layout = layouts.findForUpdate(projectId).orElse(null);
        if (layout == null) {
            layout = new BlueprintLayout(projectId, BlueprintJson.write(positions));
        } else {
            layout.setPositions(BlueprintJson.write(positions));
        }
        return layouts.saveAndFlush(layout);
    }

    /** A new block goes where the client dropped it, or at the bottom of its column. */
    private void placeNew(Long projectId, BlueprintNode node, Double x, Double y) {
        Map<String, BlueprintViews.BlueprintPoint> positions = positionsOf(projectId);
        BlueprintViews.BlueprintPoint point;
        if (x != null && y != null && Double.isFinite(x) && Double.isFinite(y)) {
            point = new BlueprintViews.BlueprintPoint(Math.round(x), Math.round(y));
        } else {
            int column = BlueprintVocabulary.columnOf(node.getKind(),
                    BlueprintVocabulary.layerOf(node.getKind(), node.getLanguage(), node.getFilePath()));
            double colX = column * (double) BlueprintLayoutEngine.COLUMN_WIDTH;
            double bottom = positions.values().stream().mapToDouble(BlueprintViews.BlueprintPoint::y).max().orElse(-BlueprintLayoutEngine.ROW_HEIGHT);
            point = new BlueprintViews.BlueprintPoint(colX, bottom + BlueprintLayoutEngine.ROW_HEIGHT);
        }
        positions.put(String.valueOf(node.getId()), point);
        savePositions(projectId, positions);
    }

    // --- the project ----------------------------------------------------------------

    Project requireProject(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /** The project, locked shared while this transaction decides on it (ADR-006 L2), and not archived. */
    Project requireWritableProject(Long projectId) {
        Project project = projects.findByIdForShare(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        return project;
    }

    BlueprintNode requireNode(Long projectId, Long nodeId) {
        return nodes.findByIdAndProjectId(nodeId, projectId)
                .orElseThrow(() -> new BlueprintNodeNotFoundException(projectId, nodeId));
    }

    // --- validation and paths -------------------------------------------------------

    private static void validate(NodeDraft draft) {
        if (draft.kind() == null) {
            throw new RequestValidationException("kind", "is required");
        }
        if (draft.name() == null || draft.name().isBlank()) {
            throw new RequestValidationException("name", "is required");
        }
        if (draft.name().strip().length() > 255) {
            throw new RequestValidationException("name", "must be at most 255 characters");
        }
        if (draft.tags() != null && draft.tags().size() > MAX_TAGS) {
            throw new RequestValidationException("tags", "at most " + MAX_TAGS + " tags");
        }
        requireLength("description", draft.description(), 4000);
        requireLength("pseudocode", draft.pseudocode(), 8000);
        requireLength("notes", draft.notes(), 4000);
        requireLength("filePath", draft.filePath(), 1000);
        requireLength("language", draft.language(), 32);
        if (draft.pathState() == PathState.MISSING) {
            throw new RequestValidationException("pathState", "MISSING is established by a scan, not chosen");
        }
    }

    private static void requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new RequestValidationException(field, "must be at most " + max + " characters");
        }
    }

    /**
     * The path of a manual block and what it is. LINKED is a fact the server
     * checks, never a claim it accepts: a path that names no file is
     * TO_CREATE, DOCUMENT_REFERENCE or VIRTUAL, as the operator says (default
     * DOCUMENT_REFERENCE), and no path at all is VIRTUAL.
     */
    private void applyPath(Long projectId, BlueprintNode node, NodeDraft draft) {
        String path = normalizePath(draft.filePath());
        node.setFilePath(path);
        if (path == null) {
            node.setPathState(PathState.VIRTUAL);
            return;
        }
        Optional<Path> folder = workspace.folderOf(projectId);
        if (folder.isPresent()) {
            Path file;
            try {
                file = WorkspacePaths.inside(folder.get(), path);
            } catch (WorkspacePathRefusedException refused) {
                throw new RequestValidationException("filePath", refused.getMessage());
            }
            if (Files.isRegularFile(file)) {
                node.setPathState(PathState.LINKED);
                if (node.getLanguage() == null) {
                    node.setLanguage(languageOf(path));
                }
                return;
            }
        }
        PathState chosen = draft.pathState();
        if (chosen == PathState.LINKED) {
            throw new RequestValidationException("pathState",
                    "'" + path + "' is not a file of the project: choose TO_CREATE, DOCUMENT_REFERENCE or VIRTUAL");
        }
        node.setPathState(chosen == null ? PathState.DOCUMENT_REFERENCE : chosen);
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    static String languageOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return "typescript";
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs")) return "javascript";
        if (p.endsWith(".py")) return "python";
        if (p.endsWith(".sql")) return "sql";
        if (p.endsWith(".cs")) return "csharp";
        if (p.endsWith(".md")) return "markdown";
        return null;
    }

    private static List<String> tags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().filter(Objects::nonNull).map(String::strip).filter(t -> !t.isEmpty())
                .map(t -> cut(t, 40)).distinct().toList();
    }

    private static <T> void override(Set<String> overrides, String field, T wanted, T detected, java.util.function.Consumer<T> set) {
        set.accept(wanted);
        if (Objects.equals(wanted, detected)) {
            overrides.remove(field);
        } else {
            overrides.add(field);
        }
    }

    private Optional<String> fromLinkedFile(Long projectId, BlueprintNode node) {
        if (node.getPathState() != PathState.LINKED || node.getFilePath() == null) {
            return Optional.empty();
        }
        Optional<Path> folder = workspace.folderOf(projectId);
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path file = WorkspacePaths.inside(folder.get(), node.getFilePath());
            String text = Files.readString(file, StandardCharsets.UTF_8);
            BlueprintScanner scanner = BlueprintScanner.withDefaultPlugins();
            BlueprintScanner.Result result = scanner.analyze(List.of(new BlueprintLanguagePlugin.SourceFile(node.getFilePath(), text)), false);
            return result.components().stream()
                    .filter(c -> c.name().equalsIgnoreCase(node.getName()) || result.components().size() == 1)
                    .map(BlueprintLanguagePlugin.Component::pseudocode).filter(p -> p != null && !p.isBlank()).findFirst()
                    .or(() -> result.components().stream().map(BlueprintLanguagePlugin.Component::pseudocode)
                            .filter(p -> p != null && !p.isBlank()).findFirst());
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A pseudocode skeleton from what the operator wrote in the structure. */
    static String skeleton(BlueprintNode node) {
        CodeStructure s = BlueprintJson.structure(node.getStructure());
        List<String> lines = new ArrayList<>();
        if (s.httpMethod() != null || s.route() != null) {
            lines.add("ON " + Optional.ofNullable(s.httpMethod()).orElse("?") + " " + Optional.ofNullable(s.route()).orElse("?"));
            if (s.requestDto() != null) lines.add("  VALIDATE " + s.requestDto());
            if (s.service() != null) lines.add("  CALL " + s.service());
            lines.add("  RETURN " + Optional.ofNullable(s.responseDto()).orElse("response"));
        }
        for (CodeStructure.CodeMethod m : s.methods()) {
            lines.add(m.name() + "(" + String.join(", ", m.parameters()) + "):");
            lines.add(m.pseudocode() == null || m.pseudocode().isBlank() ? "  TODO" : m.pseudocode().indent(2).stripTrailing());
        }
        if (s.signature() != null) {
            lines.add(s.signature() + ":");
            s.calls().forEach(c -> lines.add("  CALL " + c));
            lines.add("  RETURN " + Optional.ofNullable(s.output()).orElse(""));
        }
        if (lines.isEmpty()) {
            lines.add("// " + node.getName() + ": descrivi i passi principali");
            lines.add("INPUT …");
            lines.add("DO …");
            lines.add("RETURN …");
        }
        return cut(String.join("\n", lines), 8000);
    }

    // --- views ------------------------------------------------------------------------

    private BlueprintViews.BlueprintCanvasLayout layoutOf(Long projectId, List<BlueprintNode> all) {
        Optional<BlueprintLayout> layout = layouts.findById(projectId);
        Set<String> own = all.stream().map(n -> String.valueOf(n.getId())).collect(Collectors.toSet());
        Map<String, BlueprintViews.BlueprintPoint> positions = layout.map(l -> BlueprintJson.positions(l.getPositions()))
                .orElseGet(LinkedHashMap::new);
        positions.keySet().retainAll(own);
        return new BlueprintViews.BlueprintCanvasLayout(positions, layout.map(BlueprintLayout::getVersion).orElse(0L));
    }

    private static BlueprintViews.BlueprintCanvasLayout layoutView(BlueprintLayout layout) {
        return new BlueprintViews.BlueprintCanvasLayout(BlueprintJson.positions(layout.getPositions()), layout.getVersion());
    }

    static BlueprintViews.BlueprintBlock summary(BlueprintNode n) {
        CodeStructure s = BlueprintJson.structure(n.getStructure());
        String preview = n.getPseudocode() == null ? null
                : n.getPseudocode().lines().limit(8).collect(Collectors.joining("\n"));
        return new BlueprintViews.BlueprintBlock(n.getId(), n.getKey(), n.getKind(),
                BlueprintVocabulary.layerOf(n.getKind(), n.getLanguage(), n.getFilePath()),
                BlueprintVocabulary.of(n.getKind()).family(), n.getOrigin(), n.getStatus(), n.getPathState(), n.getName(),
                n.getFilePath(), n.getLanguage(), n.getDescription(), preview, BlueprintJson.tags(n.getTags()),
                n.getParentId(), n.isExpanded(), !s.methods().isEmpty(),
                s.methods().size() + s.fields().size() + s.columns().size(), n.getVersion());
    }

    BlueprintViews.BlueprintBlockDetail detail(BlueprintNode n) {
        List<BlueprintEdge> touching = edges.findAllTouching(n.getId());
        Set<Long> others = touching.stream().map(e -> e.getSourceNodeId().equals(n.getId()) ? e.getTargetNodeId() : e.getSourceNodeId())
                .collect(Collectors.toSet());
        Map<Long, BlueprintNode> byId = nodes.findAllById(others).stream().collect(Collectors.toMap(BlueprintNode::getId, x -> x));
        List<BlueprintViews.BlueprintConnection> incoming = new ArrayList<>();
        List<BlueprintViews.BlueprintConnection> outgoing = new ArrayList<>();
        for (BlueprintEdge e : touching) {
            boolean out = e.getSourceNodeId().equals(n.getId());
            BlueprintNode other = byId.get(out ? e.getTargetNodeId() : e.getSourceNodeId());
            if (other == null) {
                continue;
            }
            BlueprintViews.BlueprintConnection c = new BlueprintViews.BlueprintConnection(e.getId(), e.getRelation(), e.getOrigin(), e.getLabel(),
                    other.getId(), other.getName(), other.getKind());
            (out ? outgoing : incoming).add(c);
        }
        BlueprintJson.Detected d = BlueprintJson.detected(n.getDetected());
        CodeStructure s = BlueprintJson.structure(n.getStructure());
        java.time.Instant lastScanAt = n.getLastScanId() == null ? null
                : scans.findById(n.getLastScanId()).map(BlueprintScan::getFinishedAt).orElse(null);
        return new BlueprintViews.BlueprintBlockDetail(n.getId(), n.getKey(), n.getKind(),
                BlueprintVocabulary.layerOf(n.getKind(), n.getLanguage(), n.getFilePath()),
                BlueprintVocabulary.of(n.getKind()).family(), BlueprintVocabulary.of(n.getKind()).section(), n.getOrigin(),
                n.getStatus(), n.getPathState(), n.getName(), n.getFilePath(), n.getLanguage(), n.getDescription(),
                n.getPseudocode(), n.getNotes(), BlueprintJson.tags(n.getTags()), s,
                d == null ? null : new BlueprintViews.BlueprintDetectedValues(d.name(), d.kind(), d.description(), d.pseudocode()),
                List.copyOf(n.overriddenFields()), n.getStartLine(), n.getEndLine(), n.getParentId(), n.isExpanded(),
                !s.methods().isEmpty(), incoming, outgoing, lastScanAt, n.getCreatedAt(), n.getUpdatedAt(), n.getVersion());
    }

    static BlueprintViews.BlueprintRelation edge(BlueprintEdge e) {
        return new BlueprintViews.BlueprintRelation(e.getId(), e.getSourceNodeId(), e.getTargetNodeId(), e.getRelation(),
                BlueprintVocabulary.of(e.getRelation()).family(), e.getOrigin(), e.getLabel(), e.getDescription(),
                e.getCodePath() == null ? null : new BlueprintViews.BlueprintCodeReference(e.getCodePath(), e.getCodeLine(), e.getCodeExcerpt()),
                e.getVersion());
    }

    static BlueprintViews.BlueprintScanReport scan(BlueprintScan s) {
        return new BlueprintViews.BlueprintScanReport(s.getId(), s.getStartedAt(), s.getFinishedAt(), s.getRequestedBy(), s.getFiles(),
                s.isTruncated(), s.getNodesDetected(), s.getEdgesDetected(), s.getNodesAdded(), s.getNodesMissing(),
                s.getUnresolved(), s.getLanguages());
    }

    static BlueprintViews.BlueprintMetrics metrics(List<BlueprintViews.BlueprintBlock> nodes, List<BlueprintViews.BlueprintRelation> edges,
                                          int unresolved) {
        Map<String, Integer> byLayer = new LinkedHashMap<>();
        Map<String, Integer> byFamily = new LinkedHashMap<>();
        for (BlueprintVocabulary.Layer l : BlueprintVocabulary.Layer.values()) byLayer.put(l.name(), 0);
        for (BlueprintVocabulary.Family f : BlueprintVocabulary.Family.values()) byFamily.put(f.name(), 0);
        int files = 0, classes = 0, endpoints = 0, entities = 0, tests = 0, missing = 0;
        Set<String> paths = new java.util.HashSet<>();
        for (BlueprintViews.BlueprintBlock n : nodes) {
            byLayer.merge(n.layer().name(), 1, Integer::sum);
            byFamily.merge(n.family().name(), 1, Integer::sum);
            if (n.filePath() != null && n.pathState() == PathState.LINKED) paths.add(n.filePath());
            switch (n.kind()) {
                case CLASS, INTERFACE, ENUM, CONTROLLER, SERVICE, REPOSITORY, DTO, CONFIGURATION -> classes++;
                case ENDPOINT -> endpoints++;
                case ENTITY, DATABASE -> entities++;
                case TEST -> tests++;
                default -> { }
            }
            if (n.pathState() == PathState.MISSING) missing++;
        }
        files = paths.size();
        int detected = 0, manual = 0, proposed = 0;
        for (BlueprintViews.BlueprintRelation e : edges) {
            switch (e.origin()) {
                case DETECTED -> detected++;
                case MANUAL -> manual++;
                case PROPOSED -> proposed++;
            }
        }
        return new BlueprintViews.BlueprintMetrics(nodes.size(), files, classes, endpoints, entities, tests, detected, manual, proposed,
                unresolved, missing, byLayer, byFamily);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
