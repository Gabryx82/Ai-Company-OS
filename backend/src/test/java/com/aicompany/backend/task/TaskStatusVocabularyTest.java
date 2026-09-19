package com.aicompany.backend.task;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-010, TD-12 -- the closed vocabulary of {@code Task.status}.
 *
 * <p><strong>A vocabulary, not a state machine.</strong> Everything here is about
 * <em>which values exist</em>. Nothing here is about which value may follow which:
 * no transition rule is introduced by this task, and a test that asserted one
 * would be asserting a decision nobody took (ADR-011 §3).
 *
 * <p>The values are written out as literals rather than taken from the enum, and
 * that is deliberate in both directions. It let this class be committed and seen
 * red before {@code TaskStatus} existed; and it keeps the test an independent
 * statement afterwards, because a test that reads the vocabulary from the same
 * enum it is checking cannot notice the enum changing. The set itself is pinned
 * by {@code TaskStatusPinTest}, which is the one place where that comparison is
 * the point.
 */
class TaskStatusVocabularyTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTasks() {
        repository.deleteAll();
    }

    // --- the wire guard ----------------------------------------------------

    /**
     * I-1. The defect this task exists to close, in the words the audit used:
     * {@code docs/audit/CURRENT_FEATURES.md} records that {@code "banana"} is
     * accepted, and on the baseline this request answers 201.
     *
     * <p>The assertion is not only on the status code. It is on the <em>family</em>
     * of the error, and that is the decision of ADR-011 §4: a value outside the
     * vocabulary is a validation failure that names the offending field, not an
     * unreadable body. Asserting {@code type} rather than {@code title} follows
     * ADR-007 §2 -- the identifier is the contract, the sentence is prose.
     */
    @Test
    void aStatusOutsideTheVocabularyIsRejectedAsAValidationFailure() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"banana","priority":"HIGH"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:validation-failed"))
                .andExpect(jsonPath("$.errors.status").exists());

        // And nothing was written. A refusal that leaves a row behind is not a
        // refusal, and the create path composes the entity before it saves.
        assertThat(repository.count()).isZero();
    }

    /**
     * I-1, from the other side: the message has to be usable. A caller told only
     * that the value is wrong has to guess the right one, and there are three.
     *
     * <p>This pins that the allowed values appear in the message, not the exact
     * sentence around them.
     */
    @Test
    void theRefusalNamesTheValuesThatWouldHaveWorked() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"banana","priority":"HIGH"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("OPEN"),
                                org.hamcrest.Matchers.containsString("IN_PROGRESS"),
                                org.hamcrest.Matchers.containsString("DONE"))));
    }

    /**
     * I-2. {@code "open"} is outside the vocabulary exactly as much as
     * {@code "banana"} is, and the audit listed both as accepted today.
     *
     * <p>Case sensitivity is a decision and not an oversight (ADR-011 §5). The
     * database distinguishes {@code OPEN} from {@code open}, so a vocabulary that
     * accepted both would need to pick one to store -- which is a normalisation
     * rule, and this task does not introduce one. It is also the opposite choice
     * from agent names, where ADR-008 made uniqueness case-insensitive: a name is
     * something a person types, a status is something a program sends.
     */
    @Test
    void theVocabularyIsCaseSensitive() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"open","priority":"HIGH"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:validation-failed"))
                .andExpect(jsonPath("$.errors.status").exists());

        assertThat(repository.count()).isZero();
    }

    /**
     * I-3, and the guard that points the other way.
     *
     * <p><strong>This test is green on the baseline, and that is not a mistake.</strong>
     * Free strings accept the three values already. It is not here to prove the
     * defect -- the two above do that -- but to make the vocabulary fail if it is
     * closed too tightly. Narrowing an enum after rows carry a value costs a data
     * migration (ADR-004 §2), so the cheap moment to notice is now.
     *
     * <p>It also asserts the round trip: the value that comes back is byte for
     * byte the one that went in. An enum stored with {@code @Enumerated(ORDINAL)}
     * would pass the create and fail here.
     */
    @Test
    void everyValueOfTheVocabularyIsAcceptedAndComesBackUnchanged() throws Exception {

        for (String value : new String[] {"OPEN", "IN_PROGRESS", "DONE"}) {

            String location = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"t","status":"%s","priority":"HIGH"}""".formatted(value)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(value))
                    .andReturn().getResponse().getHeader("Location");

            mockMvc.perform(get(location))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(value));
        }

        assertThat(repository.count()).isEqualTo(3);
    }

    /**
     * I-9. The vocabulary is closed; the order is not constrained.
     *
     * <p>Creating a task directly in {@code DONE} is legal. It looks odd and it is
     * deliberate: refusing it would be a transition rule -- "work starts open" --
     * and no requirement states one. This test exists so that a later task cannot
     * add such a rule by accident while thinking it is tidying up; adding it on
     * purpose means deleting this test and saying why in an ADR.
     */
    @Test
    void anyValueOfTheVocabularyMayBeTheFirstOne() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"already finished when recorded","status":"DONE","priority":"LOW"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    // --- the database guard ------------------------------------------------

    /**
     * I-4. The guard that does not depend on the application being the writer.
     *
     * <p>This is the reason a Jakarta constraint alone would not close TD-12. An
     * import, a maintenance script, a hand-made correction in {@code psql} -- none
     * of them pass through the validator, and today every one of them can put a
     * value in {@code tasks.status} that the domain cannot interpret. The failure
     * would then surface at read time, far from the cause.
     *
     * <p>It is the same argument, word for word, that {@code V2} used to give
     * {@code projects.status} a check constraint on the day that table was
     * created. {@code tasks.status} never received it.
     */
    @Test
    void theDatabaseRefusesAnInsertOutsideTheVocabulary() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, status, priority) VALUES (?, ?, ?)",
                "written past the application", "banana", "HIGH"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isZero();
    }

    /**
     * I-4, on the path a check constraint is easiest to get wrong about.
     *
     * <p>A constraint that only covered inserts would leave the row reachable in
     * one {@code UPDATE}, and the insert test above would still pass. PostgreSQL
     * applies {@code CHECK} to both, so this asserts a property of the constraint
     * rather than a second one -- but it asserts it, because "both" is an
     * assumption until something fails when it is false.
     */
    @Test
    void theDatabaseRefusesAnUpdateOutsideTheVocabulary() {

        jdbc.update("INSERT INTO tasks (title, status, priority) VALUES (?, ?, ?)",
                "legitimately open", "OPEN", "HIGH");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE tasks SET status = ? WHERE title = ?", "banana", "legitimately open"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The row is still there, and still says what it said.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tasks WHERE title = ?", String.class, "legitimately open"))
                .isEqualTo("OPEN");
    }

    /**
     * I-2 at the database level. The two guards must agree about case, or one of
     * them is wrong: a value the validator refuses and the database accepts is a
     * row only a script can create, and a value the validator accepts and the
     * database refuses is a 500.
     */
    @Test
    void theDatabaseIsCaseSensitiveTooAndAgreesWithTheValidator() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, status, priority) VALUES (?, ?, ?)",
                "lowercase status", "open", "HIGH"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
