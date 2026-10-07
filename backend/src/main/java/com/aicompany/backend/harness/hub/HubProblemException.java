package com.aicompany.backend.harness.hub;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** Refusals of the Knowledge Hub across applications and marketplaces (ADR-036). */
public class HubProblemException extends ProblemException {

    private HubProblemException(ApiProblem problem, String detail) {
        super(problem, detail, Map.of());
    }

    public static HubProblemException notFound(String what) {
        return new HubProblemException(ApiProblem.SKILL_HUB_ITEM_NOT_FOUND, what);
    }

    public static HubProblemException packageRefused(String why) {
        return new HubProblemException(ApiProblem.SKILL_PACKAGE_REFUSED, why);
    }

    public static HubProblemException conflict(String why) {
        return new HubProblemException(ApiProblem.SKILL_HUB_CONFLICT, why);
    }

    public static HubProblemException readOnly(String why) {
        return new HubProblemException(ApiProblem.SKILL_LOCATION_READ_ONLY, why);
    }

    public static HubProblemException marketplace(String why) {
        return new HubProblemException(ApiProblem.MARKETPLACE_UNAVAILABLE, why);
    }
}
