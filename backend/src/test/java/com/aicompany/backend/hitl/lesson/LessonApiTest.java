package com.aicompany.backend.hitl.lesson;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 37 (ADR-044): learning that is done, not read. The lesson is a file in the
 * project; the API gives it to the operator without its answers, checks what the
 * operator does -- a prediction, a step copied into its file, an exercise -- against
 * the file and the project's folder, and keeps what they did.
 */
class LessonApiTest extends AbstractPostgresTest {

    private static final String PATH = "lessons/TASK-001.lezione.md";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private Path workspace;
    private long projectId;

    @BeforeEach
    void aProjectWithALesson() throws Exception {
        clean();
        workspace = Files.createDirectories(folder.resolve("biblioteca"));
        Files.createDirectories(workspace.resolve("lessons"));
        Files.writeString(workspace.resolve(PATH), LessonParserTest.LESSON);
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lezioni P37\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM projects WHERE name = 'Lezioni P37'");
    }

    private JsonNode view() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/lessons/view").param("path", PATH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(String route, String body) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/projects/" + projectId + "/lessons/" + route)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode step(int index) throws Exception {
        return send("step", "{\"path\":\"" + PATH + "\",\"step\":" + index + "}");
    }

    private JsonNode exercise(int index, String answer) throws Exception {
        return send("exercise", "{\"path\":\"" + PATH + "\",\"exercise\":" + index + ",\"answer\":\"" + answer + "\"}");
    }

    // --- reading ------------------------------------------------------------------------

    @Test
    void theLessonIsGivenWithoutItsAnswers() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/lessons")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].path").value(PATH))
                .andExpect(jsonPath("$[0].title").value("Il modello di un prestito"))
                .andExpect(jsonPath("$[0].task").value("TASK-001"))
                .andExpect(jsonPath("$[0].items").value(8))
                .andExpect(jsonPath("$[0].done").value(0))
                .andExpect(jsonPath("$[0].completed").value(false));

        JsonNode lesson = view();

        assertThat(lesson.path("intro").asString()).contains("cartoncino di una biblioteca");
        assertThat(lesson.path("steps")).hasSize(3);
        assertThat(lesson.path("steps").get(0).path("lines").get(0).path("code").asString()).isEqualTo("public record Prestito(");
        // What the operator has to find out is not in what the console receives.
        assertThat(lesson.path("prediction").path("answered").asBoolean()).isFalse();
        assertThat(lesson.path("prediction").path("answer").isNull()).isTrue();
        for (JsonNode exercise : lesson.path("exercises")) {
            assertThat(exercise.path("solution").isNull()).as(exercise.path("kind").asString()).isTrue();
            assertThat(exercise.path("explanation").isNull()).isTrue();
            assertThat(exercise.path("keyPoints")).isEmpty();
        }
        String raw = lesson.toString();
        assertThat(raw).doesNotContain("oggi non è dopo domani").doesNotContain("Un record è immutabile")
                .doesNotContain("vuol dire che oggi è dopo");
        assertThat(lesson.path("exercises").get(0).path("options")).extracting(JsonNode::asString)
                .containsExactly("true", "false", "Un errore");
        // The concepts are what the lesson leaves at the end.
        assertThat(lesson.path("concepts")).isEmpty();
    }

    // --- doing --------------------------------------------------------------------------

    @Test
    void aPredictionIsGivenBeforeTheAnswerIsShown() throws Exception {
        JsonNode answered = send("prediction", "{\"path\":\"" + PATH + "\",\"answer\":\"Secondo me sì\"}");

        assertThat(answered.path("answer").asString()).startsWith("Sì:");
        JsonNode lesson = view();
        assertThat(lesson.path("prediction").path("answered").asBoolean()).isTrue();
        assertThat(lesson.path("prediction").path("yourAnswer").asString()).isEqualTo("Secondo me sì");
        assertThat(lesson.path("done").asInt()).isEqualTo(1);
        // One item of eight: the lesson is not finished, and it has left no card yet.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_concepts WHERE project_id = ?", Integer.class, projectId)).isZero();

        mockMvc.perform(post("/api/projects/" + projectId + "/lessons/prediction").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + PATH + "\",\"answer\":\"  \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void aStepIsDoneWhenTheProjectsFileHoldsItsCode() throws Exception {
        // Nothing written yet.
        JsonNode missing = step(0);
        assertThat(missing.path("status").asString()).isEqualTo("FILE_MISSING");
        assertThat(missing.path("done").asBoolean()).isFalse();

        // Written with one line changed: the check names the line.
        Path source = workspace.resolve("src/prestiti/Prestito.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package prestiti;

                public record Prestito(String libro, LocalDate restituzione) {
                    public boolean scaduto(LocalDate oggi) {
                        return oggi.isBefore(restituzione);
                    }
                }
                """);
        JsonNode different = step(0);
        assertThat(different.path("status").asString()).isEqualTo("DIFFERENT");
        assertThat(different.path("detail").asString()).contains("return oggi.isAfter(restituzione);");
        assertThat(view().path("steps").get(0).path("done").asBoolean()).isFalse();

        // Written as the lesson says -- with the operator's own indentation, and a file that holds more than the step.
        Files.writeString(source, """
                package prestiti;

                import java.time.LocalDate;

                public record Prestito(String libro,   LocalDate restituzione) {
                  public boolean scaduto(LocalDate oggi) {
                      // la mia nota
                      return oggi.isAfter(restituzione);
                  }
                }
                """);
        JsonNode written = step(0);
        assertThat(written.path("status").asString()).isEqualTo("WRITTEN");
        assertThat(written.path("done").asBoolean()).isTrue();
        assertThat(view().path("steps").get(0).path("done").asBoolean()).isTrue();

        // The lines of a step are looked for in order: the right lines in the wrong order are not the step.
        Files.createDirectories(workspace.resolve("src/prestiti"));
        Files.writeString(workspace.resolve("src/prestiti/Biblioteca.java"), "}\nclass Biblioteca {\n");
        assertThat(step(2).path("status").asString()).isEqualTo("DIFFERENT");
        Files.writeString(workspace.resolve("src/prestiti/Biblioteca.java"), "class Biblioteca {\n}\n");
        assertThat(step(2).path("status").asString()).isEqualTo("WRITTEN");

        // On the operator's word, for a step they adapted.
        JsonNode marked = send("step", "{\"path\":\"" + PATH + "\",\"step\":1,\"manual\":true}");
        assertThat(marked.path("status").asString()).isEqualTo("MARKED");
        assertThat(view().path("done").asInt()).isEqualTo(3);
    }

    @Test
    void aStepThatNamesAFileOutsideTheProjectIsNotLookedFor() throws Exception {
        Path outside = folder.resolve("fuori.java");
        Files.writeString(outside, "class Segreto {\n}\n");
        Files.writeString(workspace.resolve(PATH), LessonParserTest.LESSON
                .replace("`src/prestiti/Biblioteca.java` (già scritto)", "`../fuori.java` (già scritto)")
                .replace("class Biblioteca {", "class Segreto {"));

        JsonNode refused = step(2);

        assertThat(refused.path("status").asString()).isEqualTo("PATH_REFUSED");
        assertThat(refused.path("done").asBoolean()).isFalse();
    }

    @Test
    void exercisesAreCheckedHereAndTheSolutionComesWithTheRightAnswer() throws Exception {
        // Quiz: wrong, then right. The solution is given only when the exercise is done.
        JsonNode wrong = exercise(0, "0");
        assertThat(wrong.path("correct").asBoolean()).isFalse();
        assertThat(wrong.path("done").asBoolean()).isFalse();
        assertThat(wrong.path("solution").isNull()).isTrue();
        assertThat(wrong.path("explanation").isNull()).isTrue();
        JsonNode right = exercise(0, "1");
        assertThat(right.path("correct").asBoolean()).isTrue();
        assertThat(right.path("attempts").asInt()).isEqualTo(2);
        assertThat(right.path("solution").asString()).isEqualTo("1");
        assertThat(right.path("explanation").asString()).isEqualTo("oggi non è dopo domani.");

        // Fill: right at the first try, backticks and spaces aside.
        JsonNode fill = exercise(1, " `isAfter` ");
        assertThat(fill.path("correct").asBoolean()).isTrue();
        // Bug: the line.
        assertThat(exercise(2, "riga 2").path("correct").asBoolean()).isFalse();
        assertThat(exercise(2, "3").path("correct").asBoolean()).isTrue();
        // Explain: written, compared with the key points, not judged.
        JsonNode explained = exercise(3, "Perché un record non cambia dopo che l'ho creato");
        assertThat(explained.path("correct").isNull()).isTrue();
        assertThat(explained.path("done").asBoolean()).isTrue();
        assertThat(explained.path("keyPoints")).hasSize(2);

        JsonNode lesson = view();
        assertThat(lesson.path("exercises").get(0).path("firstTryRight").asBoolean()).isFalse();
        assertThat(lesson.path("exercises").get(1).path("firstTryRight").asBoolean()).isTrue();
        assertThat(lesson.path("exercises").get(2).path("firstTryRight").asBoolean()).isFalse();
        assertThat(lesson.path("exercises").get(3).path("firstTryRight").isNull()).isTrue();
        assertThat(lesson.path("exercises").get(3).path("yourAnswer").asString()).startsWith("Perché un record");
        // Done stays done: answering again changes nothing.
        assertThat(exercise(1, "isBefore").path("correct").asBoolean()).isTrue();
        assertThat(view().path("exercises").get(1).path("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void givingUpAnExerciseShowsTheSolutionAndIsNotARightAnswer() throws Exception {
        JsonNode revealed = send("exercise", "{\"path\":\"" + PATH + "\",\"exercise\":1,\"reveal\":true}");

        assertThat(revealed.path("done").asBoolean()).isTrue();
        assertThat(revealed.path("correct").asBoolean()).isFalse();
        assertThat(revealed.path("solution").asString()).isEqualTo("isAfter");
        assertThat(view().path("exercises").get(1).path("firstTryRight").asBoolean()).isFalse();

        // Without an answer and without giving up, there is nothing to check.
        mockMvc.perform(post("/api/projects/" + projectId + "/lessons/exercise").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + PATH + "\",\"exercise\":0}")).andExpect(status().isBadRequest());
    }

    // --- finishing, and coming back ------------------------------------------------------

    private void finishTheLesson() throws Exception {
        send("prediction", "{\"path\":\"" + PATH + "\",\"answer\":\"sì\"}");
        for (int i = 0; i < 3; i++) {
            send("step", "{\"path\":\"" + PATH + "\",\"step\":" + i + ",\"manual\":true}");
        }
        exercise(0, "1");
        exercise(1, "isAfter");
        exercise(2, "3");
        exercise(3, "Un record non cambia e si scrive meno");
    }

    @Test
    void aFinishedLessonLeavesItsConceptsAsCardsThatComeBack() throws Exception {
        finishTheLesson();

        JsonNode lesson = view();
        assertThat(lesson.path("completed").asBoolean()).isTrue();
        assertThat(lesson.path("concepts")).extracting(c -> c.path("name").asString()).containsExactly("Record", "isAfter");
        // Tomorrow, not today.
        mockMvc.perform(get("/api/learning/review")).andExpect(status().isOk())
                .andExpect(jsonPath("$.due[?(@.projectName == 'Lezioni P37')]").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_concepts WHERE project_id = ? AND due_on = CURRENT_DATE + 1",
                Integer.class, projectId)).isEqualTo(2);

        jdbc.update("UPDATE learning_concepts SET due_on = CURRENT_DATE WHERE project_id = ?", projectId);
        JsonNode review = json.readTree(mockMvc.perform(get("/api/learning/review")).andReturn().getResponse().getContentAsString());
        JsonNode card = null;
        for (JsonNode due : review.path("due")) {
            if (due.path("projectName").asString().equals("Lezioni P37") && due.path("name").asString().equals("Record")) {
                card = due;
            }
        }
        assertThat(card).isNotNull();
        // The front of the card: the explanation is on the back.
        assertThat(card.path("explanation").asString()).isEmpty();
        long id = card.path("id").asLong();
        mockMvc.perform(get("/api/learning/review/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("un tipo che tiene insieme dei dati e non cambia dopo la creazione"));

        // Known: it moves up a box and comes back in three days. Not known: back to the first box, tomorrow.
        mockMvc.perform(post("/api/learning/review/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"known\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.box").value(2)).andExpect(jsonPath("$.reviews").value(1));
        assertThat(jdbc.queryForObject("SELECT due_on - CURRENT_DATE FROM learning_concepts WHERE id = ?", Integer.class, id)).isEqualTo(3);
        mockMvc.perform(post("/api/learning/review/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"known\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.box").value(1));
        assertThat(jdbc.queryForObject("SELECT due_on - CURRENT_DATE FROM learning_concepts WHERE id = ?", Integer.class, id)).isEqualTo(1);

        // Going through the lesson again makes no second set of cards.
        send("step", "{\"path\":\"" + PATH + "\",\"step\":0,\"manual\":true}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_concepts WHERE project_id = ?", Integer.class, projectId)).isEqualTo(2);
    }

    @Test
    void theLearningOfAllProjectsIsOnePageAndTheProjectsFiguresCountItsLessons() throws Exception {
        finishTheLesson();

        JsonNode overview = json.readTree(mockMvc.perform(get("/api/learning")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode mine = null;
        for (JsonNode project : overview.path("projects")) {
            if (project.path("projectId").asLong() == projectId) {
                mine = project;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.path("lessons").get(0).path("completed").asBoolean()).isTrue();
        assertThat(overview.path("stats").path("lessonsCompleted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(overview.path("cards").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(overview.path("streakDays").asInt()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/projects/" + projectId + "/learning")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons.lessons").value(1))
                .andExpect(jsonPath("$.lessons.lessonsCompleted").value(1))
                .andExpect(jsonPath("$.lessons.stepsWritten").value(3))
                .andExpect(jsonPath("$.lessons.exercisesAnswered").value(4))
                .andExpect(jsonPath("$.lessons.exercisesRightFirstTry").value(3))
                .andExpect(jsonPath("$.lessons.predictions").value(1));
    }

    // --- what is not there ----------------------------------------------------------------

    @Test
    void aLessonAnItemOrACardThatIsNotThereIsNotFound() throws Exception {
        // Files that exist in the project and are not lessons: only a .lezione.md of lessons/ is one.
        Files.writeString(workspace.resolve("AGENTS.md"), LessonParserTest.LESSON);
        Files.createDirectories(workspace.resolve("tasks"));
        Files.writeString(workspace.resolve("tasks/TASK-001.md"), LessonParserTest.LESSON);
        Files.writeString(workspace.resolve("lessons/appunti.md"), LessonParserTest.LESSON);
        for (String path : new String[] {"lessons/TASK-999.lezione.md", "AGENTS.md", "lessons/../AGENTS.md", "tasks/TASK-001.md",
                "lessons/appunti.md"}) {
            mockMvc.perform(get("/api/projects/" + projectId + "/lessons/view").param("path", path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:lesson-not-found"));
        }
        mockMvc.perform(post("/api/projects/" + projectId + "/lessons/step").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + PATH + "\",\"step\":9}")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/" + projectId + "/lessons/exercise").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + PATH + "\",\"exercise\":9,\"answer\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/learning/review/999999999")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/learning/review/999999999").contentType(MediaType.APPLICATION_JSON).content("{\"known\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/999999999/lessons")).andExpect(status().isNotFound());
    }

    @Test
    void aProjectWithoutLessonsHasAnEmptyList() throws Exception {
        Files.delete(workspace.resolve(PATH));

        mockMvc.perform(get("/api/projects/" + projectId + "/lessons")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
