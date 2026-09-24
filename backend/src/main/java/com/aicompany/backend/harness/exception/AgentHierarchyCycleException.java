package com.aicompany.backend.harness.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class AgentHierarchyCycleException extends ProblemException {

    public AgentHierarchyCycleException(String detail) {
        super(ApiProblem.AGENT_HIERARCHY_CYCLE, detail);
    }
}
