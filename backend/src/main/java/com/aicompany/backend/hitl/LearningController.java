package com.aicompany.backend.hitl;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** PHASE 32 (TD-45): the learning metrics of a project and the level they suggest (ADR-022 §5). */
@RestController
public class LearningController {

    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    /** Reads only: the suggestion is applied, if at all, by the operator on the project's profile. */
    @GetMapping("/api/projects/{id}/learning")
    public LearningService.Learning projectLearning(@PathVariable Long id) {
        return learning.of(id);
    }
}
