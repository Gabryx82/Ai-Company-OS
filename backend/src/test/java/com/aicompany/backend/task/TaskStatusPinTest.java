package com.aicompany.backend.task;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-010, invariants I-7 and I-8 -- the vocabulary is what somebody decided,
 * and all of its guards say the same thing.
 *
 * <p>ADR-011 §6 puts the vocabulary in three places: a Jakarta constraint at the
 * wire, an enum in the domain, and a check constraint in the database. Three
 * guards are stronger than one only while they agree. A value the validator
 * refuses and the database accepts is a row only a script can create; a value
 * the validator accepts and the database refuses is a 500 on a legal request.
 *
 * <p>The SQL cannot read the enum, so the set is written out by hand in
 * {@code V7}. That duplication is the single place the two can drift, which is
 * why it is compared here rather than trusted.
 */
class TaskStatusPinTest extends AbstractPostgresTest {

    /**
     * The vocabulary, written out. This is the one test allowed to do that: every
     * other one reads {@link TaskStatus}, and a test that took the expected set
     * from the enum it is checking could not notice the enum changing.
     *
     * <p>Editing this line is how adding or removing a status becomes a decision
     * instead of an edit. If you are here because a build went red, the question
     * is not whether to update the list -- it is whether {@code V8} exists, and
     * whether narrowing the set needs a data migration (ADR-004 §2).
     */
    private static final Set<String> VOCABULARY = Set.of("OPEN", "IN_PROGRESS", "DONE");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theEnumIsExactlyTheDecidedVocabulary() {

        assertThat(Arrays.stream(TaskStatus.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(VOCABULARY);
    }

    /**
     * I-8. The database declares the decided vocabulary, read out of the
     * constraint itself rather than inferred by probing values -- probing can
     * show that the members are accepted, it cannot show that nothing else is.
     *
     * <p><strong>It compares the constraint to {@link #VOCABULARY}, not to the
     * enum, and the name says so since a mutation caught the older one lying.</strong>
     * Widening the enum alone left a test called
     * {@code theCheckConstraintDeclaresTheSameSetAsTheEnum} green, because that is
     * not what it was comparing.
     *
     * <p>The triangulation is deliberate and it is the stronger arrangement, not a
     * workaround: each guard is checked against a set written out independently,
     * so agreement between the enum and the database follows transitively from
     * this test and {@link #theEnumIsExactlyTheDecidedVocabulary} together.
     * Comparing the two guards directly to each other would pass the day somebody
     * changed both and decided neither.
     */
    @Test
    void theCheckConstraintDeclaresTheDecidedVocabulary() {

        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, "tasks_status_check");

        assertThat(definition)
                .as("V7 must have created tasks_status_check")
                .isNotNull();

        Matcher literals = Pattern.compile("'([^']*)'").matcher(definition);
        Set<String> declared = literals.results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());

        assertThat(declared).isEqualTo(VOCABULARY);
    }

    /**
     * The message a rejected caller sees is derived from the enum, so it cannot
     * go stale when a member is added. Pinning that here means the derivation
     * itself is covered, not only its output in one request.
     */
    @Test
    void theRenderedVocabularyNamesEveryValue() {

        String rendered = TaskStatus.vocabulary();

        assertThat(rendered).contains(VOCABULARY);
        assertThat(rendered.split(",\\s*")).hasSize(VOCABULARY.size());
    }

    /**
     * I-2, at the level of the comparison itself rather than through a request.
     * {@code contains} is what both the validator and every other caller go
     * through, so its case sensitivity is the property, not an implementation
     * detail of the endpoint.
     */
    @Test
    void membershipIsExactAndCaseSensitive() {

        assertThat(TaskStatus.contains("OPEN")).isTrue();
        assertThat(TaskStatus.contains("IN_PROGRESS")).isTrue();
        assertThat(TaskStatus.contains("DONE")).isTrue();

        assertThat(TaskStatus.contains("open")).isFalse();
        assertThat(TaskStatus.contains("Open")).isFalse();
        assertThat(TaskStatus.contains("in_progress")).isFalse();
        assertThat(TaskStatus.contains("banana")).isFalse();
        assertThat(TaskStatus.contains("")).isFalse();
        assertThat(TaskStatus.contains(null)).isFalse();
    }
}
