package com.aicompany.backend.binding;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** A model and an execution target that do not go together, or a run asked of an agent that works elsewhere (ADR-025). */
public class BindingProblemException extends ProblemException {

    private BindingProblemException(ApiProblem problem, String detail, Map<String, String> errors) {
        super(problem, detail, errors);
    }

    public static BindingProblemException invalid(String field, String why) {
        return new BindingProblemException(ApiProblem.BINDING_INVALID, why, Map.of(field, why));
    }

    public static BindingProblemException targetNotFound(String key) {
        return new BindingProblemException(ApiProblem.TARGET_NOT_FOUND, "No execution target '" + key + "'", Map.of());
    }

    public static BindingProblemException noBaseline() {
        return new BindingProblemException(ApiProblem.NO_BASELINE, null, Map.of());
    }

    public static BindingProblemException worksElsewhere(String detail) {
        return new BindingProblemException(ApiProblem.AGENT_WORKS_ELSEWHERE, detail, Map.of());
    }
}
