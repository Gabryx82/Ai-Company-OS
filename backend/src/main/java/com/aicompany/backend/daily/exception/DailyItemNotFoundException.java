package com.aicompany.backend.daily.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class DailyItemNotFoundException extends ProblemException {

    public DailyItemNotFoundException(Long id) {
        super(ApiProblem.DAILY_ITEM_NOT_FOUND, "No daily item with id " + id);
    }
}
