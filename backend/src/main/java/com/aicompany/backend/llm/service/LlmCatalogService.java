package com.aicompany.backend.llm.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.llm.exception.ModelNotFoundException;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelProvider;
import com.aicompany.backend.llm.model.ModelRole;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.llm.repository.ModelProviderRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Providers and models as catalogs (ADR-018), joined at read time with what the
 * AI Engine reports as available -- the catalog says what a model is for, the
 * engine says whether it can answer now. An engine that does not answer leaves
 * availability unknown rather than false.
 */
@Service
@Transactional
public class LlmCatalogService {

    /** A catalogued model, with the engine's view of it when the engine answered. */
    public record ModelView(LlmModel model, Boolean engineAvailable, String engineDetail) {
    }

    /** A model the engine serves and nobody catalogued: usable, and shown as such. */
    public record Uncatalogued(String key, String provider, boolean available, boolean billed) {
    }

    public record Catalog(List<ModelView> models, List<Uncatalogued> uncatalogued, boolean engineReachable,
                          String engineDefault) {
    }

    private final ModelProviderRepository providers;
    private final LlmModelRepository models;
    private final EngineClient engine;

    public LlmCatalogService(ModelProviderRepository providers, LlmModelRepository models, EngineClient engine) {
        this.providers = providers;
        this.models = models;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public List<ModelProvider> providers() {
        return providers.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Catalog catalog() {
        Optional<EngineClient.ModelList> live = liveModels();
        Map<String, EngineClient.ModelInfo> byId = live
                .map(list -> list.models().stream()
                        .collect(Collectors.toMap(EngineClient.ModelInfo::id, Function.identity(), (a, b) -> a)))
                .orElse(Map.of());

        List<LlmModel> catalogued = models.findAllByOrderByProviderKeyAscKeyAsc();
        List<ModelView> views = catalogued.stream().map(model -> {
            if (live.isEmpty()) {
                return new ModelView(model, null, "The AI Engine did not answer");
            }
            EngineClient.ModelInfo info = byId.get(model.getKey());
            return info == null
                    ? new ModelView(model, false, "The engine does not serve this model")
                    : new ModelView(model, info.available(), info.detail());
        }).toList();

        List<String> known = catalogued.stream().map(LlmModel::getKey).toList();
        List<Uncatalogued> extra = byId.values().stream()
                .filter(info -> !known.contains(info.id()))
                // "ollama:*" style placeholders the engine reports for an unreachable provider
                .filter(info -> !info.id().endsWith(":*"))
                .map(info -> new Uncatalogued(info.id(), info.provider(), info.available(), info.billed()))
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();

        return new Catalog(views, extra, live.isPresent(), live.map(EngineClient.ModelList::defaultModel).orElse(null));
    }

    @Transactional(readOnly = true)
    public Optional<LlmModel> find(String key) {
        return models.findByKey(key);
    }

    public LlmModel classify(String key, ModelRole role, ModelLifecycle lifecycle, String replacedBy, String notes,
                             Precondition precondition) {
        LlmModel model = models.findByKeyForUpdate(key).orElseThrow(() -> new ModelNotFoundException(key));
        precondition.requireSatisfiedBy(model.getVersion());
        model.classify(role, lifecycle, replacedBy, notes);
        models.flush();
        return model;
    }

    private Optional<EngineClient.ModelList> liveModels() {
        try {
            return Optional.of(engine.models());
        } catch (EngineFailure unreachable) {
            return Optional.empty();
        }
    }
}
