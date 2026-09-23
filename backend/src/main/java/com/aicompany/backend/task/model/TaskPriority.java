package com.aicompany.backend.task.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed vocabulary of {@link Task#getPriority()} (TD-36, TASK-016).
 *
 * <p>The same shape as {@link TaskStatus}, for the same reasons, decided by the
 * same method: a census first ({@code tasks/TASK-016/CENSUS.md}). Every source --
 * fixtures, git history, the real development database -- held two values,
 * {@code HIGH} and {@code LOW}, spelled exactly so. Both are kept verbatim; no row
 * had to be rewritten to fit.
 *
 * <p>{@link #MEDIUM} is the one value no source held, and it is added on purpose:
 * a scale of two has no middle, and "neither urgent nor ignorable" is where most
 * work sits. It is the argument that gave {@link TaskStatus#IN_PROGRESS} its place
 * -- a vocabulary that cannot say the common case forces every client to lie.
 * {@code CRITICAL}, {@code URGENT}, {@code NONE} are plausible and not required,
 * and stay out by ADR-004 §2: widening costs a migration, narrowing costs a data
 * migration.
 *
 * <p>Declaration order is low to high and it is <em>not</em> what is stored --
 * {@code STRING}, never {@code ORDINAL}, for the reason every enum here gives.
 */
public enum TaskPriority {

    LOW,
    MEDIUM,
    HIGH;

    /** Exact, case-sensitive membership: {@code "high"} is as wrong as {@code "urgent"}. */
    public static boolean contains(String candidate) {
        return candidate != null && Arrays.stream(values())
                .anyMatch(priority -> priority.name().equals(candidate));
    }

    /** The vocabulary as a readable list, derived so no message can drift from it. */
    public static String vocabulary() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
