package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class ProposalDecidedException extends ProblemException {

    public ProposalDecidedException(Long proposalId, String status) {
        super(ApiProblem.BLUEPRINT_PROPOSAL_DECIDED, "Proposal " + proposalId + " is already " + status);
    }
}
