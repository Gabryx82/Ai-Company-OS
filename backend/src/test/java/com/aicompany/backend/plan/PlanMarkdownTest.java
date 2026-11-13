package com.aicompany.backend.plan;

import com.aicompany.backend.plan.service.PlanMarkdown;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 35 (ADR-041 §10): where an outcome lives, and what of a task document a run reads. */
class PlanMarkdownTest {

    @Test
    void theOutcomeFileSitsBesideItsTaskDocument() {
        assertThat(PlanMarkdown.outcomePath("tasks/TASK-001.md")).isEqualTo("tasks/TASK-001.esito.md");
        assertThat(PlanMarkdown.outcomePath("TASK.md")).isEqualTo("TASK.esito.md");
        assertThat(PlanMarkdown.outcomePath("tasks/notes")).isEqualTo("tasks/notes.esito.md");
    }

    @Test
    void aRunReadsTheWorkingSectionsInTheirOrderAndNotTheOutcome() {
        String document = """
                # TASK-007 — Indici

                > Progetto: Biblioteca

                ## Obiettivo

                Indici per le ricerche.

                ## Perché esiste

                Le ricerche sono lente.

                ## Scope

                Solo SQL.

                ## Come implementarla

                CREATE INDEX.

                ## Software e tool necessari

                - psql

                ## File da leggere

                - `AGENTS.md`

                ## Contratti, API, schema interessati

                - tabella loans

                ## Test richiesti

                - una ricerca usa l'indice

                ## Criteri di completamento

                - EXPLAIN mostra l'indice

                ## Come si consegna

                Dalla console.

                ## Esito

                Una storia lunga.

                ## Esito — secondo giro

                Un'altra storia.

                ## Note

                Note.
                """;
        assertThat(PlanMarkdown.forRun(document)).isEqualTo("""
                # TASK-007 — Indici

                ## Obiettivo

                Indici per le ricerche.

                ## Scope

                Solo SQL.

                ## Come implementarla

                CREATE INDEX.

                ## File da leggere

                - `AGENTS.md`

                ## Contratti, API, schema interessati

                - tabella loans

                ## Test richiesti

                - una ricerca usa l'indice

                ## Criteri di completamento

                - EXPLAIN mostra l'indice""");
    }

    /**
     * How to implement it, the tests and the completion criteria are kept, but lighter: each item (a line
     * of a list, a sentence of a paragraph) loses its causal explanation, and over the limit the items that
     * fit are kept in order and the cut is marked. The other sections come whole.
     */
    @Test
    void theGuidanceSectionsAreCompactedItemByItemAndTheOthersComeWhole() {
        String document = """
                # TASK-012 — Navbar

                ## Obiettivo

                Una navbar, perché serve navigare.

                ## Come implementarla

                Usa IntersectionObserver su una sentinella, perché un listener su scroll rende la pagina scattosa. \
                Su desktop il link attivo ha aria-current, perché un colore non basta a uno screen reader.
                Nessuna azione solo in hover.

                ## Test richiesti

                - Il link attivo ha aria-current, because screen readers need it
                - Il menu si chiude con Esc

                ## Criteri di completamento

                - Usabile a 360 px
                """;
        assertThat(PlanMarkdown.forRun(document, 800)).isEqualTo("""
                # TASK-012 — Navbar

                ## Obiettivo

                Una navbar, perché serve navigare.

                ## Come implementarla

                Usa IntersectionObserver su una sentinella. Su desktop il link attivo ha aria-current. \
                Nessuna azione solo in hover.

                ## Test richiesti

                - Il link attivo ha aria-current
                - Il menu si chiude con Esc

                ## Criteri di completamento

                - Usabile a 360 px""");
    }

    @Test
    void overTheLimitTheItemsThatFitAreKeptInOrderAndTheCutIsMarked() {
        String document = "# T\n\n## Test richiesti\n\n- Primo test, abbastanza lungo\n- Secondo test, abbastanza lungo\n"
                + "- Terzo test che non entra\n\n## Come implementarla\n\nPrima frase. Seconda frase. Terza frase.\n";
        assertThat(PlanMarkdown.forRun(document, 70)).isEqualTo("# T\n\n## Test richiesti\n\n"
                + "- Primo test, abbastanza lungo\n- Secondo test, abbastanza lungo\n[…]\n\n"
                + "## Come implementarla\n\nPrima frase. Seconda frase. Terza frase.");
        // An item is never split: the first one stays whole, whatever the limit.
        assertThat(PlanMarkdown.forRun(document, 20)).contains("- Primo test, abbastanza lungo\n[…]")
                .contains("Prima frase.\n[…]");
    }

    @Test
    void aDocumentWithoutThoseSectionsLosesOnlyItsEsitoAndWhatFollows() {
        String document = "# Una task scritta a mano\n\nFai questo e quello.\n\n## Esito\n\nStoria.\n\n## Note\n\nAltro.\n";
        assertThat(PlanMarkdown.forRun(document)).isEqualTo("# Una task scritta a mano\n\nFai questo e quello.");
        assertThat(PlanMarkdown.forRun("Solo testo.")).isEqualTo("Solo testo.");
    }

    @Test
    void aSectionWithNothingInItIsNotSent() {
        assertThat(PlanMarkdown.forRun("# T\n\n## Obiettivo\n\nFare.\n\n## Scope\n\n—\n\n## Test richiesti\n\n—\n"))
                .isEqualTo("# T\n\n## Obiettivo\n\nFare.");
    }
}
