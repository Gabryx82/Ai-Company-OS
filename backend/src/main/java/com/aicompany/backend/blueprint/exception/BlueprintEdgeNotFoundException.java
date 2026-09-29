package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class BlueprintEdgeNotFoundException extends ProblemException {

    public BlueprintEdgeNotFoundException(Long projectId, Long edgeId) {
        super(ApiProblem.BLUEPRINT_EDGE_NOT_FOUND, "No relation " + edgeId + " in the Blueprint of project " + projectId);
    }
}
