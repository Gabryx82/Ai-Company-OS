package com.aicompany.backend.run.controller;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.exception.EngineUnavailableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The engine's model list, relayed (TASK-021).
 *
 * <p>So that the console can offer a choice of models without holding the
 * engine's token: the browser talks to the control plane only, and the service
 * credential never leaves it (ADR-001 boundary).
 */
@RestController
public class EngineController {

    private final EngineClient engine;

    public EngineController(EngineClient engine) {
        this.engine = engine;
    }

    @GetMapping("/api/engine/models")
    public EngineClient.ModelList models() {
        try {
            return engine.models();
        } catch (EngineFailure failure) {
            throw new EngineUnavailableException(failure.detail());
        }
    }
}
