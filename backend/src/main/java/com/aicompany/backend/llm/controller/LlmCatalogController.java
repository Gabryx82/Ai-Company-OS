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

    public LlmCatalogController(LlmCatalogService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public List<ProviderResponse> providers() {
        return service.providers().stream().map(ProviderResponse::from).toList();
    }

    @GetMapping("/models")
    public ModelCatalogResponse models() {
        return ModelCatalogResponse.from(service.catalog());
    }

    @PutMapping("/models")
    public ResponseEntity<ModelResponse> classify(
            @RequestParam String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ModelClassificationRequest request) {
        LlmModel model = service.classify(key, request.role(), request.lifecycle(), request.replacedBy(),
                request.notes(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok()
                .eTag(ETags.of(model.getVersion()))
                .body(ModelResponse.from(new LlmCatalogService.ModelView(model, null, null)));
    }
}
