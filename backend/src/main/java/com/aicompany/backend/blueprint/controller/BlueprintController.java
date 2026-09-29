package com.aicompany.backend.blueprint.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.blueprint.model.AiAction;
import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.EdgeOrigin;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.NodeStatus;
import com.aicompany.backend.blueprint.model.PathState;
import com.aicompany.backend.blueprint.model.ProposalKind;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.service.BlueprintScanService;
import com.aicompany.backend.blueprint.service.BlueprintService;
import com.aicompany.backend.blueprint.service.BlueprintViews;
import com.aicompany.backend.blueprint.service.ChangeProposalService;
import com.aicompany.backend.security.Caller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.util.List;
import java.util.Map;

/**
 * The Project Blueprint (ADR-033): the components of a project and their
 * relations, detected from the code or drawn by the operator. Every mutation of
 * an existing row takes {@code If-Match} (ADR-009); none writes a source file.
 */
@RestController
public class BlueprintController {

    public record BlueprintBlockRequest(NodeKind kind, String name, String filePath, String language, String description,
                              String pseudocode, String notes, List<String> tags, NodeStatus status, PathState pathState,
                              CodeStructure structure, Double x, Double y) {
        BlueprintService.NodeDraft draft() {
            return new BlueprintService.NodeDraft(kind, name, filePath, language, description, pseudocode, notes, tags,
                    status, pathState, structure, x, y);
        }
    }

    public record BlueprintRelationRequest(Long sourceId, Long targetId, RelationType relation, EdgeOrigin origin, String label,
                              String description) {
        BlueprintService.EdgeDraft draft() {
            return new BlueprintService.EdgeDraft(sourceId, targetId, relation, origin, label, description);
        }
    }

    public record BlueprintRevertRequest(List<String> fields) {
    }

    public record BlueprintLayoutRequest(Map<String, BlueprintViews.BlueprintPoint> positions) {
    }

    public record BlueprintActionRequest(AiAction action) {
    }

    public record BlueprintProposalRequest(ProposalKind kind, Long nodeId, Long edgeId, String filePath, String title,
                                  String reason, String consequences, String diff) {
        ChangeProposalService.ProposalDraft draft() {
            return new ChangeProposalService.ProposalDraft(kind, nodeId, edgeId, filePath, title, reason, consequences, diff);
        }
    }

    private static final String BASE = "/api/projects/{id}/blueprint";

    private final BlueprintService blueprint;
    private final BlueprintScanService scanner;
    private final ChangeProposalService proposals;

    public BlueprintController(BlueprintService blueprint, BlueprintScanService scanner, ChangeProposalService proposals) {
        this.blueprint = blueprint;
        this.scanner = scanner;
        this.proposals = proposals;
    }

    // --- the graph --------------------------------------------------------------------

    @GetMapping("/api/blueprint/vocabulary")
    public BlueprintViews.BlueprintVocabularyView blueprintVocabulary() {
        return blueprint.vocabulary();
    }

    @GetMapping(BASE)
    public BlueprintViews.BlueprintGraph blueprintGraph(@PathVariable Long id) {
        return blueprint.graph(id);
    }

    /** Reads the code into the Blueprint (ADR-033 §4). */
    @PostMapping(BASE + "/scan")
    public BlueprintViews.BlueprintScanReport blueprintScan(@PathVariable Long id, @AuthenticationPrincipal Caller caller) {
        return scanner.scan(id, caller.name());
    }

    @GetMapping(BASE + "/scans")
    public List<BlueprintViews.BlueprintScanReport> blueprintScans(@PathVariable Long id) {
        return blueprint.scans(id);
    }

    // --- blocks -----------------------------------------------------------------------

    @PostMapping(BASE + "/nodes")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> createBlueprintNode(@PathVariable Long id, @RequestBody BlueprintBlockRequest request) {
        BlueprintViews.BlueprintBlockDetail node = blueprint.createNode(id, request.draft());
        return ResponseEntity.created(URI.create("/api/projects/" + id + "/blueprint/nodes/" + node.id()))
                .eTag(ETags.of(node.version())).body(node);
    }

    @GetMapping(BASE + "/nodes/{nodeId}")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> blueprintNode(@PathVariable Long id, @PathVariable Long nodeId) {
        BlueprintViews.BlueprintBlockDetail node = blueprint.node(id, nodeId);
        return ResponseEntity.ok().eTag(ETags.of(node.version())).body(node);
    }

    @PutMapping(BASE + "/nodes/{nodeId}")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> updateBlueprintNode(@PathVariable Long id, @PathVariable Long nodeId,
                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                @RequestBody BlueprintBlockRequest request) {
        return versioned(blueprint.updateNode(id, nodeId, request.draft(), Precondition.fromHeader(ifMatch)));
    }

    @DeleteMapping(BASE + "/nodes/{nodeId}")
    public ResponseEntity<Void> deleteBlueprintNode(@PathVariable Long id, @PathVariable Long nodeId,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        blueprint.deleteNode(id, nodeId, Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** Back to the code: the overrides named (all when none is) are dropped. */
    @PostMapping(BASE + "/nodes/{nodeId}/revert")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> revertBlueprintNode(@PathVariable Long id, @PathVariable Long nodeId,
                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                @RequestBody(required = false) BlueprintRevertRequest request) {
        return versioned(blueprint.revertNode(id, nodeId, request == null ? List.of() : request.fields(),
                Precondition.fromHeader(ifMatch)));
    }

    /** Generate/update pseudocode, deterministically, from the code or the structure. */
    @PostMapping(BASE + "/nodes/{nodeId}/pseudocode")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> regenerateBlueprintPseudocode(@PathVariable Long id, @PathVariable Long nodeId,
                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return versioned(blueprint.regeneratePseudocode(id, nodeId, Precondition.fromHeader(ifMatch)));
    }

    @PostMapping(BASE + "/nodes/{nodeId}/expand")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> expandBlueprintNode(@PathVariable Long id, @PathVariable Long nodeId,
                                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return versioned(blueprint.expandNode(id, nodeId, Precondition.fromHeader(ifMatch)));
    }

    @PostMapping(BASE + "/nodes/{nodeId}/collapse")
    public ResponseEntity<BlueprintViews.BlueprintBlockDetail> collapseBlueprintNode(@PathVariable Long id, @PathVariable Long nodeId,
                                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return versioned(blueprint.collapseNode(id, nodeId, Precondition.fromHeader(ifMatch)));
    }

    @GetMapping(BASE + "/nodes/{nodeId}/snippet")
    public BlueprintViews.BlueprintSnippet blueprintSnippet(@PathVariable Long id, @PathVariable Long nodeId,
                                          @RequestParam(defaultValue = "false") boolean full) {
        return blueprint.snippet(id, nodeId, full);
    }

    /** An AI action becomes a task of the project (ADR-033 §8). */
    @PostMapping(BASE + "/nodes/{nodeId}/ai-actions")
    public ResponseEntity<BlueprintViews.BlueprintActionOutcome> requestBlueprintAiAction(@PathVariable Long id, @PathVariable Long nodeId,
                                                                 @RequestBody BlueprintActionRequest request,
                                                                 @AuthenticationPrincipal Caller caller) {
        BlueprintViews.BlueprintActionOutcome outcome = proposals.requestAiAction(id, nodeId, request.action(), caller.name());
        return ResponseEntity.created(URI.create("/api/tasks/" + outcome.taskId())).body(outcome);
    }

    // --- relations --------------------------------------------------------------------

    @PostMapping(BASE + "/edges")
    public ResponseEntity<BlueprintViews.BlueprintRelation> createBlueprintEdge(@PathVariable Long id, @RequestBody BlueprintRelationRequest request) {
        BlueprintViews.BlueprintRelation edge = blueprint.createEdge(id, request.draft());
        return ResponseEntity.created(URI.create("/api/projects/" + id + "/blueprint/edges/" + edge.id()))
                .eTag(ETags.of(edge.version())).body(edge);
    }

    @PutMapping(BASE + "/edges/{edgeId}")
    public ResponseEntity<BlueprintViews.BlueprintRelation> updateBlueprintEdge(@PathVariable Long id, @PathVariable Long edgeId,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @RequestBody BlueprintRelationRequest request) {
        BlueprintViews.BlueprintRelation edge = blueprint.updateEdge(id, edgeId, request.draft(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(edge.version())).body(edge);
    }

    @DeleteMapping(BASE + "/edges/{edgeId}")
    public ResponseEntity<Void> deleteBlueprintEdge(@PathVariable Long id, @PathVariable Long edgeId,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        blueprint.deleteEdge(id, edgeId, Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(BASE + "/edges/{edgeId}/confirm")
    public ResponseEntity<BlueprintViews.BlueprintRelation> confirmBlueprintEdge(@PathVariable Long id, @PathVariable Long edgeId,
                                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        BlueprintViews.BlueprintRelation edge = blueprint.confirmEdge(id, edgeId, Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(edge.version())).body(edge);
    }

    // --- layout -----------------------------------------------------------------------

    @PutMapping(BASE + "/layout")
    public ResponseEntity<BlueprintViews.BlueprintCanvasLayout> saveBlueprintLayout(@PathVariable Long id,
                                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                            @RequestBody BlueprintLayoutRequest request) {
        BlueprintViews.BlueprintCanvasLayout layout = blueprint.saveLayout(id, request.positions() == null ? Map.of() : request.positions(),
                Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(layout.version())).body(layout);
    }

    /** Reset layout: the server's automatic layout for every block (ADR-033 §6). */
    @PostMapping(BASE + "/layout/auto")
    public ResponseEntity<BlueprintViews.BlueprintCanvasLayout> autoBlueprintLayout(@PathVariable Long id,
                                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        BlueprintViews.BlueprintCanvasLayout layout = blueprint.autoLayout(id, Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(layout.version())).body(layout);
    }

    // --- proposed code changes --------------------------------------------------------

    @GetMapping(BASE + "/proposals")
    public List<BlueprintViews.BlueprintProposal> blueprintProposals(@PathVariable Long id) {
        return proposals.list(id);
    }

    @PostMapping(BASE + "/proposals")
    public ResponseEntity<BlueprintViews.BlueprintProposal> proposeBlueprintChange(@PathVariable Long id, @RequestBody BlueprintProposalRequest request,
                                                           @AuthenticationPrincipal Caller caller) {
        BlueprintViews.BlueprintProposal proposal = proposals.propose(id, request.draft(), caller.name());
        return ResponseEntity.created(URI.create("/api/projects/" + id + "/blueprint/proposals/" + proposal.id()))
                .eTag(ETags.of(proposal.version())).body(proposal);
    }

    /** Accepting creates the task that will change the code; the diff is not applied here. */
    @PostMapping(BASE + "/proposals/{proposalId}/accept")
    public ResponseEntity<BlueprintViews.BlueprintProposal> acceptBlueprintProposal(@PathVariable Long id, @PathVariable Long proposalId,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @AuthenticationPrincipal Caller caller) {
        BlueprintViews.BlueprintProposal proposal = proposals.accept(id, proposalId, Precondition.fromHeader(ifMatch), caller.name());
        return ResponseEntity.ok().eTag(ETags.of(proposal.version())).body(proposal);
    }

    @PostMapping(BASE + "/proposals/{proposalId}/reject")
    public ResponseEntity<BlueprintViews.BlueprintProposal> rejectBlueprintProposal(@PathVariable Long id, @PathVariable Long proposalId,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @AuthenticationPrincipal Caller caller) {
        BlueprintViews.BlueprintProposal proposal = proposals.reject(id, proposalId, Precondition.fromHeader(ifMatch), caller.name());
        return ResponseEntity.ok().eTag(ETags.of(proposal.version())).body(proposal);
    }

    private static ResponseEntity<BlueprintViews.BlueprintBlockDetail> versioned(BlueprintViews.BlueprintBlockDetail node) {
        return ResponseEntity.ok().eTag(ETags.of(node.version())).body(node);
    }
}
