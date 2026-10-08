package com.aicompany.backend.hitl;

import com.aicompany.backend.project.model.AutonomyLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 32 (TD-45): the edges of the rule that suggests a level (ADR-022, revision of PHASE 32). */
class LearningSuggestionTest {

    private static LearningService.LevelSuggestion suggest(AutonomyLevel level, Integer pieces, Boolean... firstPass) {
        return LearningService.suggest(level, List.of(firstPass), pieces);
    }

    @Test
    void eightyPercentOfTheWindowIsEnoughAndLessIsNot() {
        assertThat(suggest(AutonomyLevel.GUIDED, 0, true, true, false, true, true).direction())
                .isEqualTo(LearningService.Direction.UP);
        assertThat(suggest(AutonomyLevel.GUIDED, 0, true, false, false, true, true).direction())
                .isEqualTo(LearningService.Direction.STAY);
    }

    @Test
    void onlyTheLastTasksOfTheWindowCount() {
        // Two early rejections fall out of the window of five.
        assertThat(suggest(AutonomyLevel.GUIDED, 0, false, false, true, true, true, true, true).direction())
                .isEqualTo(LearningService.Direction.UP);
    }

    @Test
    void nothingAboveTheTopNothingBelowTheBottom() {
        assertThat(suggest(AutonomyLevel.FINAL_REVIEW, null, true, true, true, true, true, true, true, true, true, true))
                .extracting(LearningService.LevelSuggestion::direction, LearningService.LevelSuggestion::level)
                .containsExactly(LearningService.Direction.STAY, AutonomyLevel.FINAL_REVIEW);
        assertThat(suggest(AutonomyLevel.GUIDED, 0, false, false, false, false).direction())
                .as("GUIDED is the floor: it stays, it is not stepped down")
                .isEqualTo(LearningService.Direction.STAY);
        assertThat(suggest(AutonomyLevel.FINAL_REVIEW, null, true, false, false, false))
                .extracting(LearningService.LevelSuggestion::direction, LearningService.LevelSuggestion::level)
                .containsExactly(LearningService.Direction.DOWN, AutonomyLevel.DELEGATED);
    }

    @Test
    void withoutAFolderThePiecesAreUnknownAndSaidSo() {
        LearningService.LevelSuggestion up = suggest(AutonomyLevel.GUIDED, null, true, true, true, true, true);
        assertThat(up.direction()).isEqualTo(LearningService.Direction.UP);
        assertThat(up.reasons()).anyMatch(reason -> reason.contains("non si possono contare"));
    }
}
