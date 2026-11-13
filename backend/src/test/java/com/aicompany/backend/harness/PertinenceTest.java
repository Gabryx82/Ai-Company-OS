package com.aicompany.backend.harness;

import com.aicompany.backend.harness.library.Essentials;
import com.aicompany.backend.harness.library.Pertinence;
import com.aicompany.backend.harness.model.HarnessResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 35 (ADR-041, phase 2): which skills and knowledge are pertinent to a task, and
 * which part of one goes into an engine run. One deterministic rule each, in one place;
 * no model decides.
 */
class PertinenceTest {

    private static HarnessResource skill(String key, String name, String description, String... tags) {
        return new HarnessResource(key, HarnessResource.Kind.SKILL, name, description, List.of(tags), null, null, null);
    }

    private static final HarnessResource SQL_MODELING = skill("sql-modeling", "Modellazione dati e migrazioni",
            "Schema normalizzato, vincoli nel database, migrazioni additive e verificate.", "database", "sql", "migrations");
    private static final HarnessResource SQL_STYLE = skill("sql-postgres-style", "Stile SQL PostgreSQL",
            "Convenzioni per lo schema PostgreSQL.");
    private static final HarnessResource TDD = skill("tdd-workflow", "Test-Driven Development",
            "Rosso prima del verde: test che falliscono per la ragione giusta.", "testing");
    private static final HarnessResource REVIEW = skill("code-review", "Review avversariale del diff",
            "Cercare l'input che rompe l'invariante, il test che resterebbe verde senza la riga che protegge.");
    private static final HarnessResource REACT = skill("react-typescript", "React e TypeScript",
            "Componenti funzionali, hook, tipi dal contratto.", "frontend", "react");

    // ---------------------------------------------------------------- pertinence

    @Test
    void aWordOfTheKeyNameOrTagsWeighsTwoAWordOfTheDescriptionOne() {
        String task = "Progettare la migrazione dei prestiti. Solo il file SQL e i vincoli.";
        // migrazione ~ migrazioni (name) 2, sql (key) 2, vincoli (description) 1
        assertThat(Pertinence.score(task, SQL_MODELING)).isEqualTo(5);
        assertThat(Pertinence.score(task, SQL_STYLE)).isEqualTo(2);
        assertThat(Pertinence.score(task, REACT)).isZero();
    }

    @Test
    void singularAndPluralMatchButAShortPrefixDoesNot() {
        // Italian plurals and English -s match; "testo" is not "test", "righe" is not "riga".
        assertThat(Pertinence.score("Scrivi i test della tabella", TDD)).isEqualTo(2);
        assertThat(Pertinence.score("Niente altro testo, al massimo cinque righe", TDD)).isZero();
        assertThat(Pertinence.score("Niente altro testo, al massimo cinque righe", REVIEW)).isZero();
        assertThat(Pertinence.score("Aggiungi le migration del modulo", SQL_MODELING)).isEqualTo(2);
    }

    @Test
    void wordsCarryNoAccentsCaseOrFunctionWords() {
        assertThat(Pertinence.score("REACT: componenti e hook", REACT)).isEqualTo(2 + 1 + 1);
        assertThat(Pertinence.score("Della, delle, nella, come, solo, dopo", REVIEW)).isZero();
        HarnessResource accented = skill("qualita", "Qualità del codice", "Leggibilità.");
        assertThat(Pertinence.score("La qualita del modulo", accented)).isEqualTo(2);
    }

    @Test
    void atMostTheGivenNumberTheMostPertinentFirstOnlyAboveTheThreshold() {
        String task = "Progettare la migrazione dei prestiti con i suoi test. Solo SQL, con i vincoli.";
        List<HarnessResource> candidates = List.of(TDD, REACT, SQL_STYLE, SQL_MODELING, REVIEW);

        // sql-modeling 5; tdd-workflow and sql-postgres-style 2 each, and tdd comes first among the candidates.
        assertThat(Pertinence.select(task, candidates, 2)).containsExactly(SQL_MODELING, TDD);
        // A single word of a description is not enough to be pertinent.
        assertThat(Pertinence.select("Controlla l'invariante", List.of(REVIEW), 2)).isEmpty();
        assertThat(Pertinence.select("", candidates, 2)).isEmpty();
    }

    @Test
    void equalScoresKeepTheCandidatesOrderSoTheChoiceIsTheSameEveryTime() {
        HarnessResource other = skill("sql-naming", "Nomi SQL", "Convenzioni.");
        // "SQL": 2 and 2 -- the order of the candidates decides, every time.
        assertThat(Pertinence.select("SQL", List.of(other, SQL_STYLE), 2)).containsExactly(other, SQL_STYLE);
        assertThat(Pertinence.select("SQL", List.of(SQL_STYLE, other), 2)).containsExactly(SQL_STYLE, other);
        // "Lo schema SQL": sql-postgres-style also has "schema" (3 against 2), wherever it stands.
        assertThat(Pertinence.select("Lo schema SQL", List.of(other, SQL_STYLE), 1)).containsExactly(SQL_STYLE);
    }

    // ---------------------------------------------------------------- the essential part

    @Test
    void anEssenzialeSectionWinsOverTheIntroduction() {
        String body = """
                # Modellazione

                Introduzione lunga.

                ## Istruzioni

                Molto testo.

                ## Essenziale

                - Ogni vincolo ha un nome.
                - Migrazioni solo additive.

                ## Esempi

                Altro.
                """;
        assertThat(Essentials.of(body, "Descrizione", 1500)).contains("- Ogni vincolo ha un nome.\n- Migrazioni solo additive.");
    }

    @Test
    void withoutItTheBodyUpToTheFirstSecondLevelHeadingWithoutTheTitle() {
        String body = """
                # Test-Driven Development

                Rosso prima del verde.

                Un comportamento alla volta.

                ## Istruzioni

                Tutto il resto.
                """;
        assertThat(Essentials.of(body, "Descrizione", 1500)).contains("Rosso prima del verde.\n\nUn comportamento alla volta.");
        assertThat(Essentials.of("- Una regola.\n- Due regole.\n", null, 1500)).contains("- Una regola.\n- Due regole.");
    }

    @Test
    void anEmptyIntroductionFallsBackToTheDescriptionAndNothingIsNothing() {
        assertThat(Essentials.of("# Titolo\n\n## Istruzioni\n\nTesto.", "Schema normalizzato.", 1500))
                .contains("Schema normalizzato.");
        assertThat(Essentials.of("## Istruzioni\n\nTesto.", null, 1500)).isEmpty();
    }

    @Test
    void overTheLimitItIsCutAtTheLastParagraphThatFitsNeverInTheMiddleOfOne() {
        String body = "Primo paragrafo.\n\nSecondo paragrafo, più lungo del primo.\n\nTerzo paragrafo che non entra.";
        String cut = Essentials.of(body, null, 60).orElseThrow();
        assertThat(cut).isEqualTo("Primo paragrafo.\n\nSecondo paragrafo, più lungo del primo.\n[…]");
        // A first paragraph longer than the limit is cut at its last line, then at its last word.
        assertThat(Essentials.of("Una riga.\nUna seconda riga molto lunga.", null, 20).orElseThrow()).isEqualTo("Una riga.\n[…]");
        assertThat(Essentials.of("parola parola parola parola", null, 15).orElseThrow()).isEqualTo("parola parola\n[…]");
    }
}
