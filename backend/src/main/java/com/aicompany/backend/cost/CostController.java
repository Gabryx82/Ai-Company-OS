package com.aicompany.backend.cost;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.llm.model.LlmModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** What runs cost and what providers may spend (ADR-031). Budgets and prices are admin decisions (/api/admin/**). */
@RestController
public class CostController {

    private final CostService costs;

    public CostController(CostService costs) {
        this.costs = costs;
    }

    public record BudgetRequest(@NotNull BigDecimal monthlyLimitUsd, Integer alertPercent) {
    }

    public record PriceRequest(BigDecimal inputPricePerMtok, BigDecimal outputPricePerMtok) {
    }

    public record PriceResponse(String model, BigDecimal inputPricePerMtok, BigDecimal outputPricePerMtok, long version) {
        static PriceResponse from(LlmModel m) {
            return new PriceResponse(m.getKey(), m.getInputPricePerMtok(), m.getOutputPricePerMtok(), m.getVersion());
        }
    }

    @GetMapping("/api/costs")
    public CostService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        return costs.summary(start, end);
    }

    @PutMapping("/api/admin/costs/budgets/{provider}")
    public CostService.Budget budget(@PathVariable String provider,
                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @Valid @RequestBody BudgetRequest body) {
        return costs.setBudget(provider, body.monthlyLimitUsd(), body.alertPercent() == null ? 80 : body.alertPercent(),
                Optional.ofNullable(ifMatch).map(Precondition::fromHeader));
    }

    @PutMapping("/api/admin/costs/prices/{model}")
    public ResponseEntity<PriceResponse> price(@PathVariable String model,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @RequestBody PriceRequest body) {
        LlmModel updated = costs.setPrice(model, body.inputPricePerMtok(), body.outputPricePerMtok(),
                Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(updated.getVersion())).body(PriceResponse.from(updated));
    }
}
