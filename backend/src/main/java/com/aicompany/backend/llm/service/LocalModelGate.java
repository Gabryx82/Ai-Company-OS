package com.aicompany.backend.llm.service;

import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ProviderKind;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.llm.repository.ModelProviderRepository;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.engine.RunFailures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.Semaphore;

/**
 * PHASE 35 (ADR-041 §12): the models of a LOCAL provider run one at a time, across task
 * runs and plan generations -- on this machine two local models loaded together take the
 * free RAM to nothing (ADR-034 §3.7). Other providers do not wait. How many may run
 * together is {@code aicos.runs.local-concurrency} (1).
 */
@Component
public class LocalModelGate {

    /** Held while a local model works; closing it lets the next one in. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final Permit NONE = () -> {
    };

    private final Semaphore permits;
    private final LlmModelRepository models;
    private final ModelProviderRepository providers;

    public LocalModelGate(@Value("${aicos.runs.local-concurrency:1}") int permits, LlmModelRepository models,
                          ModelProviderRepository providers) {
        this.permits = new Semaphore(Math.max(1, permits), true);
        this.models = models;
        this.providers = providers;
    }

    /** Whether the model's provider runs on this machine (catalog kind LOCAL). */
    @Transactional(readOnly = true)
    public boolean isLocal(String modelKey) {
        if (modelKey == null) {
            return false;
        }
        String provider = models.findByKey(modelKey).map(LlmModel::getProviderKey)
                .orElse(modelKey.contains(":") ? modelKey.substring(0, modelKey.indexOf(':')) : modelKey);
        return providers.findByKey(provider).map(p -> p.getKind() == ProviderKind.LOCAL).orElse(false);
    }

    /**
     * Waits for a turn when the model is local; returns at once otherwise. An interruption
     * while waiting -- a cancelled plan generation -- is the engine failure every caller
     * already handles as such.
     */
    public Permit enter(String modelKey) {
        if (!isLocal(modelKey)) {
            return NONE;
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineFailure(RunFailures.INTERRUPTED, "Interrupted while waiting for the local model to be free");
        }
        return permits::release;
    }
}
