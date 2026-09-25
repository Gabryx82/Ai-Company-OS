package com.aicompany.backend.graph.code;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The code graph of a project (ADR-030): the last scan, or a new one. */
@RestController
public class CodeGraphController {

    private final CodeGraphService graphs;

    public CodeGraphController(CodeGraphService graphs) {
        this.graphs = graphs;
    }

    @GetMapping("/api/projects/{id}/code-graph")
    public ResponseEntity<CodeGraphService.CodeGraph> last(@PathVariable Long id) {
        return graphs.last(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/projects/{id}/code-graph")
    public CodeGraphService.CodeGraph scan(@PathVariable Long id) {
        return graphs.scan(id);
    }
}
