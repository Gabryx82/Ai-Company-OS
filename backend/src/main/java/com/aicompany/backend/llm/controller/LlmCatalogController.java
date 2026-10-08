package com.aicompany.backend.llm.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.llm.dto.LlmCatalogDtos.ModelCatalogResponse;
import com.aicompany.backend.llm.dto.LlmCatalogDtos.ModelClassificationRequest;
import com.aicompany.backend.llm.dto.LlmCatalogDtos.ModelResponse;
import com.aicompany.backend.llm.dto.LlmCatalogDtos.ProviderResponse;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.service.LlmCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Providers and models (ADR-018). A model key contains colons and dots
 * ({@code ollama:qwen3.5:9b}), so it travels as a query parameter rather than as
 * a path segment.
 */
@RestController
@RequestMapping("/api/catalog")
public class LlmCatalogController {

    private final LlmCatalogService service;
    private final com.aicompany.backend.llm.service.ModelDeletionService deletions;

    public LlmCatalogController(LlmCatalogService service,
                                com.aicompany.backend.llm.service.ModelDeletionService deletions) {
        this.service = service;
        this.deletions = deletions;
    }

    /** PHASE 32 (TD-71): what removing a model from the catalog would take and keep. */
    @GetMapping("/models/deletion")
    public com.aicompany.backend.deletion.DeletionPreview modelDeletion(@RequestParam String key) {
        return deletions.preview(key);
    }

    /**
     * PHASE 32 (TD-71): the model leaves the catalog -- retired, if the catalog ships it.
     * Admin-only (SecurityConfiguration); takes the entry's tag.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/models")
    public ResponseEntity<Void> deleteModel(@RequestParam String key,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            org.springframework.security.core.Authentication caller) {
        deletions.delete(key, Precondition.fromHeader(ifMatch), caller.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/providers")
    public List<ProviderResponse> providers() {
        return service.providers().stream().map(ProviderResponse::from).toList();
    }

    @GetMapping("/models")
    public ModelCatalogResponse catalogModels() {
        return ModelCatalogResponse.from(service.catalog());
    }

    @PutMapping("/models")
    public ResponseEntity<ModelResponse> classify(
            @RequestParam String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ModelClassificationRequest request) {
        LlmModel model = service.classify(key, request.role(), request.lifecycle(), request.replacedBy(),
                request.notes(), request.alsoServes(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok()
                .eTag(ETags.of(model.getVersion()))
                .body(ModelResponse.from(new LlmCatalogService.ModelView(model, null, null)));
    }
}
