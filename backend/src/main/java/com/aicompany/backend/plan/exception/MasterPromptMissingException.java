package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class MasterPromptMissingException extends ProblemException {

    public MasterPromptMissingException(String detail) {
        super(ApiProblem.MASTER_PROMPT_MISSING, detail);
    }
}
