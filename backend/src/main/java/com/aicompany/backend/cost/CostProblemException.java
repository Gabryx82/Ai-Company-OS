package com.aicompany.backend.cost;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.math.BigDecimal;
import java.util.Map;

/** A pay-per-token run refused by cost governance (ADR-031). */
public class CostProblemException extends ProblemException {

    private CostProblemException(ApiProblem problem, String detail) {
        super(problem, detail, Map.of());
    }

    public static CostProblemException budgetRequired(String provider) {
        return new CostProblemException(ApiProblem.BUDGET_REQUIRED,
                provider + " bills per token: an admin must set its monthly budget before any run (Consumi → Budget)");
    }

    public static CostProblemException priceRequired(String model) {
        return new CostProblemException(ApiProblem.PRICE_REQUIRED,
                "The price of " + model + " is not known: an admin must set it, or its cost could not be counted");
    }

    public static CostProblemException budgetExceeded(String provider, BigDecimal spent, BigDecimal limit) {
        return new CostProblemException(ApiProblem.BUDGET_EXCEEDED,
                provider + " has spent " + spent + " USD this month, its budget is " + limit + " USD");
    }
}
