package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class BlueprintNodeNotFoundException extends ProblemException {

    public BlueprintNodeNotFoundException(Long projectId, Long nodeId) {
        super(ApiProblem.BLUEPRINT_NODE_NOT_FOUND, "No block " + nodeId + " in the Blueprint of project " + projectId);
    }
}
