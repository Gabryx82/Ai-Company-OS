package com.aicompany.backend.journal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 37 (ADR-047): the diary an agent keeps while it works, one line per step, read back as steps. */
class DiaryFormatTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Test
    void aNewDiaryHasItsHeaderAndNoStep() {
        String diary = DiaryFormat.template("TASK-017");
        assertThat(diary).contains("TASK-017").contains("una riga per passo");
        assertThat(DiaryFormat.parse(diary, null, null, ROME)).isEmpty();
    }

    @Test
    void readsEachStepWithTheTimeTheAgentWroteInAnyCommonShape() {
        String diary = DiaryFormat.template("TASK-017") + """
                - 2026-11-13T12:04:05+01:00 · Letto tasks/TASK-017.md e la fase 4
                - 2026-11-13T11:05:00Z — Creato src/routes/MovieDetail.tsx
                * 2026-11-13 12:07 - Eseguiti i test: 12 verdi
                - [12:09] Corretto il fallback del backdrop
                - 12:10:30 | Finito: esito in TASK-017.esito.md
                Una riga che non è un passo.
                """;
        List<DiaryFormat.Step> steps = DiaryFormat.parse(diary, Instant.parse("2026-11-13T11:10:31Z"), null, ROME);

        assertThat(steps).extracting(DiaryFormat.Step::text).containsExactly(
                "Letto tasks/TASK-017.md e la fase 4", "Creato src/routes/MovieDetail.tsx", "Eseguiti i test: 12 verdi",
                "Corretto il fallback del backdrop", "Finito: esito in TASK-017.esito.md");
        assertThat(steps).extracting(DiaryFormat.Step::at).containsExactly(
                Instant.parse("2026-11-13T11:04:05Z"), Instant.parse("2026-11-13T11:05:00Z"), Instant.parse("2026-11-13T11:07:00Z"),
                Instant.parse("2026-11-13T11:09:00Z"), Instant.parse("2026-11-13T11:10:30Z"));
        assertThat(steps).extracting(DiaryFormat.Step::approximate).containsOnly(false);
        assertThat(steps).extracting(DiaryFormat.Step::position).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void aStepWithoutATimeTakesTheOneBeforeItAndTheLastOneTheFilesTime() {
        String diary = """
                - Letto il task
                - 2026-11-13T12:04:00+01:00 · Creato il componente
                - Scritti i test
                - Eseguiti i test
                """;
        Instant delivered = Instant.parse("2026-11-13T11:00:12Z");
        Instant modified = Instant.parse("2026-11-13T11:20:00Z");
        List<DiaryFormat.Step> steps = DiaryFormat.parse(diary, modified, delivered, ROME);

        assertThat(steps).extracting(DiaryFormat.Step::at).containsExactly(
                delivered, Instant.parse("2026-11-13T11:04:00Z"), Instant.parse("2026-11-13T11:04:00Z"), modified);
        assertThat(steps).extracting(DiaryFormat.Step::approximate).containsExactly(true, false, true, true);
    }

    @Test
    void keepsTheLastStepsAndCutsAStepTooLong() {
        StringBuilder diary = new StringBuilder();
        for (int i = 0; i < DiaryFormat.MAX_STEPS + 20; i++) {
            diary.append("- 2026-11-13T12:00:00+01:00 · passo ").append(i).append('\n');
        }
        diary.append("- ").append("x".repeat(1000)).append('\n');
        List<DiaryFormat.Step> steps = DiaryFormat.parse(diary.toString(), null, null, ROME);

        assertThat(steps).hasSize(DiaryFormat.MAX_STEPS);
        assertThat(steps.getFirst().text()).isEqualTo("passo 21");
        assertThat(steps.getLast().text()).hasSize(DiaryFormat.MAX_TEXT).endsWith("…");
        assertThat(steps.getLast().position()).isEqualTo(DiaryFormat.MAX_STEPS + 20);
    }
}
