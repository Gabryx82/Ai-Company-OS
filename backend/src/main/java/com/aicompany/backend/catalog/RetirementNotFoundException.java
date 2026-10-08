package com.aicompany.backend.catalog;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/** PHASE 32 (TD-71): nothing of that catalog was retired under that key. */
public class RetirementNotFoundException extends ProblemException {
    public RetirementNotFoundException(String catalog, String key) {
        super(ApiProblem.RESOURCE_NOT_FOUND, "No retired " + catalog + " entry '" + key + "'");
    }
}
