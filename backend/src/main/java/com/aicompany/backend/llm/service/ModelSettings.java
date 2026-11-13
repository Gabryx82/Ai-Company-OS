package com.aicompany.backend.llm.service;

import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.run.engine.EngineClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE 35 (ADR-041 §11): what the catalog says about how a model runs -- its window and
 * how long Ollama keeps it loaded -- added to a request for the engine, which passes it
 * to Ollama. A model the catalog does not know, or one without settings, sends none and
 * the engine keeps its own defaults.
 */
@Component
public class ModelSettings {

    private final LlmModelRepository models;

    public ModelSettings(LlmModelRepository models) {
        this.models = models;
    }

    @Transactional(readOnly = true)
    public EngineClient.Request apply(EngineClient.Request request) {
        if (request.model() == null) {
            return request;
        }
        return models.findByKey(request.model())
                .map(m -> request.withModelSettings(m.getNumCtx(), m.getKeepAlive()))
                .orElse(request);
    }
}
