package com.aicompany.backend.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Second Brain's data (directive §17). */
@RestController
public class EcosystemGraphController {

    private final EcosystemGraphService service;

    public EcosystemGraphController(EcosystemGraphService service) {
        this.service = service;
    }

    @GetMapping("/api/graph")
    public EcosystemGraphService.Graph graph() {
        return service.graph();
    }
}
