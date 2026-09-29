package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class ChangeProposalNotFoundException extends ProblemException {

    public ChangeProposalNotFoundException(Long projectId, Long proposalId) {
        super(ApiProblem.BLUEPRINT_PROPOSAL_NOT_FOUND, "No proposed code change " + proposalId + " in project " + projectId);
    }
}
