package com.aicompany.backend.llm.dto;

import com.aicompany.backend.llm.model.Billing;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelProvider;
import com.aicompany.backend.llm.model.ModelRole;
import com.aicompany.backend.llm.model.ProviderKind;
import com.aicompany.backend.llm.model.ProviderStatus;
import com.aicompany.backend.llm.service.LlmCatalogService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The wire shapes of the provider and model catalogs (ADR-018). */
public final class LlmCatalogDtos {

    private LlmCatalogDtos() {
    }

    public record ProviderResponse(String key, String name, ProviderKind kind, @Nullable String engineProvider,
                                   Billing billing, ProviderStatus status, @Nullable String baseUrl, @Nullable String docsUrl,
                                   @Nullable String notes) {
        public static ProviderResponse from(ModelProvider p) {
            return new ProviderResponse(p.getKey(), p.getName(), p.getKind(), p.getEngineProvider(), p.getBilling(),
                    p.getStatus(), p.getBaseUrl(), p.getDocsUrl(), p.getNotes());
        }
    }

    public record ModelResponse(String key, String providerKey, String displayName, ModelRole role,
                                List<String> capabilities, @Nullable Integer contextWindow, @Nullable BigDecimal sizeGb,
                                @Nullable String parameters, ModelLifecycle lifecycle, @Nullable String replacedBy, @Nullable String notes,
                                @Nullable Boolean engineAvailable, @Nullable String engineDetail, long version,
                                @Nullable BigDecimal inputPricePerMtok, @Nullable BigDecimal outputPricePerMtok,
                                List<ModelRole> alsoServes) {
        public static ModelResponse from(LlmCatalogService.ModelView view) {
            LlmModel m = view.model();
            return new ModelResponse(m.getKey(), m.getProviderKey(), m.getDisplayName(), m.getRole(),
                    m.getCapabilities(), m.getContextWindow(), m.getSizeGb(), m.getParameters(), m.getLifecycle(),
                    m.getReplacedBy(), m.getNotes(), view.engineAvailable(), view.engineDetail(), m.getVersion(),
                    m.getInputPricePerMtok(), m.getOutputPricePerMtok(), m.getAlsoServes());
        }
    }

    public record UncataloguedModel(String key, String provider, boolean available, boolean billed) {
    }

    public record ModelCatalogResponse(List<ModelResponse> models, List<UncataloguedModel> uncatalogued,
                                       boolean engineReachable, @Nullable String engineDefault) {
        public static ModelCatalogResponse from(LlmCatalogService.Catalog catalog) {
            return new ModelCatalogResponse(
                    catalog.models().stream().map(ModelResponse::from).toList(),
                    catalog.uncatalogued().stream()
                            .map(u -> new UncataloguedModel(u.key(), u.provider(), u.available(), u.billed()))
                            .toList(),
                    catalog.engineReachable(), catalog.engineDefault());
        }
    }

    public record ModelClassificationRequest(@NotNull ModelRole role, @NotNull ModelLifecycle lifecycle,
                                             @Size(max = 200) String replacedBy, @Size(max = 2000) String notes,
                                             /** PHASE 32 (TD-66): the other roles it serves; absent keeps them as they are. */
                                             List<ModelRole> alsoServes) {
    }
}
