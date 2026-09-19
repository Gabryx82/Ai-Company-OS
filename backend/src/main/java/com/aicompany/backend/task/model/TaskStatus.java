package com.aicompany.backend.task.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed vocabulary of {@link Task#getStatus()}.
 *
 * <p><strong>A vocabulary, not a state machine.</strong> This enum says which
 * values exist. It says nothing about which value may follow which: there are no
 * transition rules in this codebase, and any member may be the one a task is
 * created with -- {@link #DONE} included, which is how work that was already
 * finished gets recorded. Refusing that would be the rule "work starts open",
 * and no requirement states one (ADR-011 §3).
 *
 * <p>The set is small on purpose, by the criterion ADR-004 §2 established for
 * projects: widening costs a migration and an enum member, narrowing after rows
 * carry a value costs a data migration. The asymmetry says to stay narrow.
 * {@code BLOCKED}, {@code CANCELLED}, {@code IN_REVIEW}, {@code DRAFT} and
 * {@code PAUSED} are all plausible and none is required by a requirement, which
 * is the same sentence that kept {@code PAUSED} out of {@link
 * com.aicompany.backend.project.model.ProjectStatus}.
 *
 * <p>Adding a member is therefore <strong>not</strong> an application-only
 * change: {@code V7} gives the database the same set through
 * {@code tasks_status_check}, so a new value needs a migration that rewrites the
 * constraint. That cost is intentional.
 *
 * <p>Stored as {@code @Enumerated(EnumType.STRING)} and never as an ordinal: an
 * ordinal makes the column unreadable outside the application, and reordering
 * the members below would silently rewrite the meaning of every row without
 * touching one.
 */
public enum TaskStatus {

    /**
     * Recorded, not yet being worked on. The only value that existed before this
     * enum did -- every row and every fixture in the repository carried it -- and
     * it is kept with exactly that spelling, so no data had to be rewritten to fit
     * the vocabulary (ADR-011 §1).
     */
    OPEN,

    /**
     * Somebody is working on it now.
     *
     * <p>This is the value TASK-009 made worth having: {@code agent_id} says whose
     * the task is, which is not the same as saying anybody has started. Without a
     * distinct value the two facts collapse into one and the difference cannot be
     * asked for.
     */
    IN_PROGRESS,

    /**
     * Finished. The terminal value -- terminal in the sense that nothing here
     * moves past it, not in the sense that a rule forbids leaving it, because no
     * rule constrains movement at all.
     */
    DONE;

    /**
     * Whether {@code candidate} is one of these, compared exactly.
     *
     * <p>Case-sensitive, and that is a decision (ADR-011 §5): {@code "open"} is
     * outside the vocabulary exactly as much as {@code "banana"} is. Accepting
     * both spellings would require choosing one to store, which is a
     * normalisation rule nobody asked for -- and without it the database would
     * hold two names for one state.
     *
     * <p>It is the opposite choice from agent names, which ADR-008 made
     * case-insensitive, and the two are not in conflict: a name is typed by a
     * person, where a difference in case is usually a typo worth forgiving; a
     * status is sent by a program, where it is a wrong constant, and forgiving it
     * hides the defect instead of fixing it.
     */
    public static boolean contains(String candidate) {
        return candidate != null && Arrays.stream(values())
                .anyMatch(status -> status.name().equals(candidate));
    }

    /**
     * The vocabulary as a readable list, for the message a rejected caller sees.
     *
     * <p>Derived from {@link #values()} rather than written out, so a member added
     * without updating a message is impossible. The one place the set is written
     * out by hand is the migration, because SQL cannot read this enum -- and a
     * test asserts the two agree.
     */
    public static String vocabulary() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
