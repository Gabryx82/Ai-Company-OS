package com.aicompany.backend.usage.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.usage.dto.UsageDtos.QuotaAnchorRequest;
import com.aicompany.backend.usage.dto.UsageDtos.UsageWindowResponse;
import com.aicompany.backend.usage.model.QuotaPlan;
import com.aicompany.backend.usage.service.UsageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Consumption and quota windows of the cloud models and tools. */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsageWindowResponse> report() {
        return service.report().stream().map(UsageWindowResponse::from).toList();
    }

    /** States when a window resets, where nothing on this machine measures it. */
    @PutMapping("/plans/{key}")
    public ResponseEntity<Void> anchor(@PathVariable String key,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @Valid @RequestBody QuotaAnchorRequest request) {
        QuotaPlan plan = service.anchor(key, request.resetWeekday(), request.resetTime(), request.resetZone(),
                request.limitNote(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().eTag(ETags.of(plan.getVersion())).build();
    }
}
