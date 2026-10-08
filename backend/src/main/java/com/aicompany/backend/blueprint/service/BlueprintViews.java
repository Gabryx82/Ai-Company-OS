package com.aicompany.backend.blueprint.service;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the Blueprint API answers (ADR-033 §9). Records, so the OpenAPI
 * description and the console's generated types follow them.
 */
public final class BlueprintViews {

    private BlueprintViews() {
    }

    /** Everything the canvas needs to draw, in one read: blocks, relations, positions, numbers. */
    public record BlueprintGraph(Long projectId, boolean archived, boolean workspaceReady, List<BlueprintBlock> nodes,
                        List<BlueprintRelation> edges, BlueprintCanvasLayout layout, @Nullable BlueprintScanReport lastScan, BlueprintMetrics metrics, int pendingProposals,
                        /** PHASE 32 (TD-61): the texts are left out; the canvas loads them as it zooms in. */
                        boolean outline,
                        /** PHASE 32 (TD-61): only the blocks of an area, and the relations between them. */
                        boolean partial) {
    }

    /**
     * A block as the canvas draws it. The full description is here (it is short);
     * the pseudocode is a preview, the structure is not -- both are in {@link NodeDetail}.
     */
    public record BlueprintBlock(Long id, String key, NodeKind kind, BlueprintVocabulary.Layer layer,
                              BlueprintVocabulary.Family family, NodeOrigin origin, NodeStatus status,
                              PathState pathState, String name, @Nullable String filePath, @Nullable String language, @Nullable String description,
                              @Nullable String pseudocodePreview, List<String> tags, @Nullable Long parentId, boolean expanded,
                              boolean expandable, int members, long version) {
    }

    /** A detected value next to the effective one, so the Inspector can say which is which. */
    public record BlueprintDetectedValues(String name, NodeKind kind, @Nullable String description, @Nullable String pseudocode) {
    }

    /** A relation as the Inspector lists it: with the name and kind of the block at the other end. */
    public record BlueprintConnection(Long edgeId, RelationType relation, EdgeOrigin origin, @Nullable String label, Long nodeId,
                             String nodeName, NodeKind nodeKind) {
    }

    public record BlueprintBlockDetail(Long id, String key, NodeKind kind, BlueprintVocabulary.Layer layer,
                             BlueprintVocabulary.Family family, BlueprintVocabulary.Section section, NodeOrigin origin,
                             NodeStatus status, PathState pathState, String name, @Nullable String filePath, @Nullable String language,
                             @Nullable String description, @Nullable String pseudocode, @Nullable String notes, List<String> tags,
                             CodeStructure structure, @Nullable BlueprintDetectedValues detected, List<String> overrides,
                             @Nullable Integer startLine, @Nullable Integer endLine, @Nullable Long parentId, boolean expanded, boolean expandable,
                             List<BlueprintConnection> incoming, List<BlueprintConnection> outgoing, @Nullable Instant lastScanAt,
                             Instant createdAt, Instant updatedAt, long version) {
    }

    public record BlueprintCodeReference(String path, @Nullable Integer line, @Nullable String excerpt) {
    }

    public record BlueprintRelation(Long id, Long source, Long target, RelationType relation,
                       BlueprintVocabulary.RelationFamily family, EdgeOrigin origin, @Nullable String label, @Nullable String description,
                       @Nullable BlueprintCodeReference code, long version) {
    }

    public record BlueprintPoint(double x, double y) {
    }

    /** Node id (as a string: JSON keys are strings) to position; the version is the layout's ETag. */
    public record BlueprintCanvasLayout(Map<String, BlueprintPoint> positions, long version) {
    }

    public record BlueprintScanReport(Long id, Instant startedAt, Instant finishedAt, String requestedBy, int files, boolean truncated,
                       int nodesDetected, int edgesDetected, int nodesAdded, int nodesMissing, int unresolved,
                       String languages) {
    }

    /** The numbers at the top of the Blueprint (directive §11). */
    public record BlueprintMetrics(int totalNodes, int files, int classes, int endpoints, int databaseEntities, int tests,
                          int detectedRelations, int manualRelations, int proposedRelations, int unresolvedRelations,
                          int missingNodes, Map<String, Integer> byLayer, Map<String, Integer> byFamily) {
    }

    /** A code excerpt of a block, read from its file on request. */
    public record BlueprintSnippet(String path, String language, int startLine, int endLine, String text, boolean truncated) {
    }

    public record BlueprintProposal(Long id, Long nodeId, @Nullable Long edgeId, ProposalKind kind, ProposalStatus status,
                           String filePath, String title, String reason, String consequences, String diff,
                           @Nullable Long taskId, String proposedBy, @Nullable String decidedBy, @Nullable Instant decidedAt, Instant createdAt,
                           long version) {
    }

    /** What an AI action created: the task that carries it through the orchestrator. */
    public record BlueprintActionOutcome(Long taskId, String taskTitle, String action) {
    }

    public record BlueprintVocabularyView(List<BlueprintVocabulary.BlueprintKindInfo> kinds, List<BlueprintVocabulary.BlueprintRelationInfo> relations,
                             List<BlueprintVocabulary.BlueprintActionInfo> actions, List<NodeStatus> statuses,
                             List<PathState> pathStates, List<ProposalKind> proposalKinds) {
    }
}
