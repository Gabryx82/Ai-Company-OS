package com.aicompany.backend.hitl.lesson;

import com.aicompany.backend.hitl.LessonFormat;
import com.aicompany.backend.hitl.lesson.Lesson.Kind;
import com.aicompany.backend.hitl.lesson.Lesson.StepMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 37 (ADR-044): a lesson file, read. The format the agents are told
 * ({@link LessonFormat#GUIDE}) and the format the parser reads are one: the first test
 * parses the guide's own example.
 */
class LessonParserTest {

    static final String LESSON = """
            # Lezione — Il modello di un prestito

            ## In parole semplici

            Un prestito è una scheda: chi ha preso che cosa, e quando lo deve restituire.
            Costruiamo quella scheda, come il cartoncino di una biblioteca.

            ## Prova a prevedere

            Se la data di restituzione è ieri, il prestito è scaduto?

            **Risposta**: Sì: «scaduto» vuol dire che oggi è dopo la data di restituzione.

            ## Passo 1 — La scheda del prestito

            **File**: `src/prestiti/Prestito.java` (nuovo)

            ```java
            public record Prestito(String libro, LocalDate restituzione) {
                public boolean scaduto(LocalDate oggi) {
                    return oggi.isAfter(restituzione);
                }
            }
            ```

            **Riga per riga**

            - `public record Prestito(` — dichiara la scheda: un tipo che tiene insieme i dati del prestito
            - `return oggi.isAfter(restituzione);` — confronta due date: vero se oggi viene dopo
              la data di restituzione

            **Perché**: un record non si può modificare dopo che è stato creato,
            quindi nessuno cambia per sbaglio la data.

            ## Passo 2 — Usarla dove serve

            **File**: `src/prestiti/Elenco.java` (modifica)

            ```java
            ## non è un titolo: è dentro il codice
            return prestiti.stream().filter(p -> p.scaduto(oggi)).toList();
            ```

            **Riga per riga**

            1. `filter(p -> p.scaduto(oggi))` - tiene solo i prestiti scaduti

            ## Passo 3 — Quello che c'era già

            **File**: `src/prestiti/Biblioteca.java` (già scritto)

            ```java
            class Biblioteca {
            }
            ```

            **Riga per riga**

            - La classe vuota è il contenitore che riempiremo.

            ## Mettiti alla prova

            ### Quiz — Che cosa restituisce `scaduto` se la restituzione è domani?

            - [ ] true
            - [x] false
            - [ ] Un errore

            **Spiegazione**: oggi non è dopo domani.

            ### Completa — Il confronto fra le date

            ```java
            return oggi.___(restituzione);
            ```

            **Soluzione**: `isAfter`

            **Spiegazione**: isAfter è vero quando la prima data viene dopo.

            ### Trova l'errore — Perché questo non compila?

            ```java
            public record Prestito(String libro) {
                public boolean scaduto(LocalDate oggi) {
                    return oggi.isAfter(restituzione);
                }
            }
            ```

            **Soluzione**: riga 3

            **Spiegazione**: restituzione non è fra i dati del record.

            ### Spiega a parole tue — Perché un record e non una classe con i setter?

            **Punti chiave**:

            - Un record è immutabile
            - Meno codice da scrivere e da sbagliare

            ## Cosa hai imparato

            - **Record**: un tipo che tiene insieme dei dati e non cambia dopo la creazione
            - **isAfter**: il confronto che dice se una data viene dopo un'altra
            """;

    @Test
    void theExampleTheAgentsAreGivenIsALessonTheConsoleCanBringToLife() {
        String guide = LessonFormat.GUIDE;
        String example = guide.substring(guide.indexOf("````markdown") + "````markdown".length(), guide.lastIndexOf("````"));

        Lesson lesson = LessonParser.parse(example);

        assertThat(lesson.problems()).as("the guide's own example follows the format").isEmpty();
        assertThat(lesson.prediction()).isNotNull();
        assertThat(lesson.steps()).hasSize(2);
        assertThat(lesson.steps().getFirst().file()).isEqualTo("percorso/del/file.ext");
        assertThat(lesson.steps().getFirst().lines()).hasSize(2);
        assertThat(lesson.exercises()).extracting(Lesson.Exercise::kind)
                .containsExactly(Kind.QUIZ, Kind.FILL, Kind.BUG, Kind.EXPLAIN);
        assertThat(lesson.concepts()).hasSize(1);
        // Both modes hand the agent this very guide.
        assertThat(LessonFormat.instructions(com.aicompany.backend.hitl.LearningMode.TEACH, "lessons/TASK-001.lezione.md"))
                .contains(guide).contains("lessons/TASK-001.lezione.md").contains("Non modificare i file del progetto");
        assertThat(LessonFormat.instructions(com.aicompany.backend.hitl.LearningMode.RECAP, "x")).contains(guide).contains("già scritto");
        assertThat(LessonFormat.instructions(com.aicompany.backend.hitl.LearningMode.NONE, "x")).isEmpty();
    }

    @Test
    void aLessonIsReadWithItsStepsTheirLinesAndTheAnswers() {
        Lesson lesson = LessonParser.parse(LESSON);

        assertThat(lesson.problems()).isEmpty();
        assertThat(lesson.title()).isEqualTo("Il modello di un prestito");
        assertThat(lesson.intro()).startsWith("Un prestito è una scheda").contains("biblioteca");
        assertThat(lesson.prediction().question()).isEqualTo("Se la data di restituzione è ieri, il prestito è scaduto?");
        assertThat(lesson.prediction().answer()).startsWith("Sì:");

        assertThat(lesson.steps()).hasSize(3);
        Lesson.Step first = lesson.steps().getFirst();
        assertThat(first.title()).isEqualTo("La scheda del prestito");
        assertThat(first.file()).isEqualTo("src/prestiti/Prestito.java");
        assertThat(first.mode()).isEqualTo(StepMode.NEW);
        assertThat(first.language()).isEqualTo("java");
        assertThat(first.code()).startsWith("public record Prestito(").endsWith("}").contains("        return oggi.isAfter(restituzione);");
        assertThat(first.lines()).hasSize(2);
        assertThat(first.lines().get(0).code()).isEqualTo("public record Prestito(");
        // An explanation that runs on the next line is one explanation.
        assertThat(first.lines().get(1).text()).isEqualTo("confronta due date: vero se oggi viene dopo la data di restituzione");
        assertThat(first.why()).isEqualTo("un record non si può modificare dopo che è stato creato, quindi nessuno cambia per sbaglio la data.");

        assertThat(lesson.steps().get(1).mode()).isEqualTo(StepMode.EDIT);
        // A heading inside a code fence is code; a numbered list and a plain hyphen are read as well.
        assertThat(lesson.steps().get(1).code()).startsWith("## non è un titolo");
        assertThat(lesson.steps().get(1).lines()).singleElement()
                .satisfies(line -> assertThat(line.text()).isEqualTo("tiene solo i prestiti scaduti"));
        assertThat(lesson.steps().get(2).mode()).isEqualTo(StepMode.DONE);
        assertThat(lesson.steps().get(2).lines().getFirst().code()).isNull();

        assertThat(lesson.exercises()).hasSize(4);
        Lesson.Exercise quiz = lesson.exercises().get(0);
        assertThat(quiz.prompt()).isEqualTo("Che cosa restituisce `scaduto` se la restituzione è domani?");
        assertThat(quiz.options()).extracting(Lesson.Option::correct).containsExactly(false, true, false);
        assertThat(quiz.explanation()).isEqualTo("oggi non è dopo domani.");
        assertThat(lesson.exercises().get(1).solution()).isEqualTo("isAfter");
        assertThat(lesson.exercises().get(1).code()).contains("___");
        assertThat(lesson.exercises().get(2).solution()).isEqualTo("3");
        assertThat(lesson.exercises().get(3).keyPoints()).containsExactly("Un record è immutabile", "Meno codice da scrivere e da sbagliare");

        assertThat(lesson.concepts()).extracting(Lesson.Concept::name).containsExactly("Record", "isAfter");
        assertThat(lesson.items()).isEqualTo(1 + 3 + 4);
    }

    @Test
    void whatDepartsFromTheFormatIsLeftOutAndSaidNeverAnError() {
        Lesson lesson = LessonParser.parse("""
                # Una lezione senza prefisso

                ## Passo 1 - Senza file

                ```js
                const a = 1;
                ```

                ## Mettiti alla prova

                ### Quiz — Due risposte giuste?

                - [x] sì
                - [x] anche

                ### Indovinello — Non è un esercizio che conosco

                ### Completa — Senza buco

                ```js
                const a = 1;
                ```

                **Soluzione**: `1`
                """);

        assertThat(lesson.title()).isEqualTo("Una lezione senza prefisso");
        assertThat(lesson.steps()).hasSize(1);
        assertThat(lesson.steps().getFirst().file()).isNull();
        assertThat(lesson.exercises()).isEmpty();
        assertThat(lesson.problems()).anyMatch(p -> p.contains("In parole semplici"))
                .anyMatch(p -> p.contains("Passo 1") && p.contains("File"))
                .anyMatch(p -> p.contains("Riga per riga"))
                .anyMatch(p -> p.contains("una sola segnata con [x]"))
                .anyMatch(p -> p.contains("Indovinello"))
                .anyMatch(p -> p.contains("___"))
                .anyMatch(p -> p.contains("Non ci sono esercizi"));

        assertThat(LessonParser.parse("").problems()).isNotEmpty();
        assertThat(LessonParser.parse(null).steps()).isEmpty();
    }

    @Test
    void windowsLineEndingsAndLabelsWithoutAccentsAreTheSameLesson() {
        Lesson lesson = LessonParser.parse(LESSON.replace("\n", "\r\n").replace("**Perché**", "**Perche**")
                .replace("(già scritto)", "(gia scritto)"));

        assertThat(lesson.problems()).isEmpty();
        assertThat(lesson.steps().getFirst().why()).startsWith("un record non si può modificare");
        assertThat(lesson.steps().get(2).mode()).isEqualTo(StepMode.DONE);
        assertThat(lesson.steps().getFirst().code()).doesNotContain("\r");
    }
}
