package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE 36 (ADR-042): a handoff or a run refused by its readiness check, before anything
 * is written or opened. The detail says why; {@code errors} names each blocking item.
 */
public class DeliveryNotReadyException extends ProblemException {

    public DeliveryNotReadyException(String target, List<ReadinessService.ReadinessItem> blocking) {
        super(ApiProblem.DELIVERY_NOT_READY, "Consegna a " + target + " ferma: "
                + String.join(" ", blocking.stream().map(ReadinessService.ReadinessItem::detail).toList()), errors(blocking));
    }

    private static Map<String, String> errors(List<ReadinessService.ReadinessItem> blocking) {
        Map<String, String> errors = new LinkedHashMap<>();
        blocking.forEach(i -> errors.put(i.area().toLowerCase() + ":" + i.key(), i.detail()));
        return errors;
    }
}
