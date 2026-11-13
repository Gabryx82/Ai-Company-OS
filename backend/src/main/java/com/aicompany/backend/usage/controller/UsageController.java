package com.aicompany.backend.usage.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.usage.dto.UsageDtos.QuotaAnchorRequest;
import com.aicompany.backend.usage.dto.UsageDtos.UsageWindowResponse;
import com.aicompany.backend.usage.model.QuotaPlan;
import com.aicompany.backend.usage.service.AgentMeasures;
import com.aicompany.backend.usage.service.UsageService;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Consumption and quota windows of the cloud models and tools. */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService service;
    private final AgentMeasures measures;

    public UsageController(UsageService service, AgentMeasures measures) {
        this.service = service;
        this.measures = measures;
    }

    @GetMapping
    public List<UsageWindowResponse> report() {
        return service.report().stream().map(UsageWindowResponse::from).toList();
    }

    /**
     * PHASE 35 (ADR-041): what the agents consumed, as measured -- per run, per model,
     * per agent, per handoff -- between two days, both included; this month by default.
     */
    @GetMapping("/measurements")
    public AgentMeasures.MeasuresReport measurements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        return measures.report(from == null ? end.withDayOfMonth(1) : from, end);
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
