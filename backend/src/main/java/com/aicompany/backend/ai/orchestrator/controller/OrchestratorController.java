package com.aicompany.backend.ai.orchestrator.controller;

import com.aicompany.backend.ai.orchestrator.model.MasterOrchestrator;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orchestrator")
public class OrchestratorController {

    private final MasterOrchestrator orchestrator;

    public OrchestratorController(MasterOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public String execute(@RequestBody String request){

        return orchestrator.analyze(request);

    }
}