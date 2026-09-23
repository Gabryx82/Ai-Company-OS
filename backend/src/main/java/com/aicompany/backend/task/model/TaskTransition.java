package com.aicompany.backend.task.model;

/**
 * The edges of the task lifecycle, and the only ones (ADR-014).
 *
 * <pre>
 *   OPEN ──START──▶ IN_PROGRESS ──COMPLETE──▶ DONE
 *    ▲                  │                       │
 *    └──────STOP────────┘                       │
 *    └────────────────REOPEN────────────────────┘
 * </pre>
 *
 * <p><strong>{@link TaskStatus} is the vocabulary, this is the machine.</strong>
 * ADR-011 §3 closed the set of values and deliberately said nothing about which
 * may follow which; this enum is where that question is answered, in one table
 * that a reader can check against the drawing above. Each member is exactly one
 * edge: a {@code from} and a {@code to}. There is no edge that a caller can
 * compose from parts.
 *
 * <p><strong>What is deliberately not an edge.</strong> {@code OPEN → DONE}:
 * finishing work nobody was recorded as doing is what creating a task as
 * {@code DONE} already covers (ADR-011 §3), and on an existing task it would
 * make "who did it" unanswerable. {@code DONE → IN_PROGRESS}: reopened work goes
 * back to the queue first, where it can be reassigned before anybody starts it.
 * Both are cheap to add later and expensive to take back once clients use them --
 * the asymmetry ADR-004 §2 used to keep vocabularies narrow applies to edges too.
 */
public enum TaskTransition {

    /** Work is taken on. The only edge with a rule about the agent: see {@link #requiresActiveAgent()}. */
    START(TaskStatus.OPEN, TaskStatus.IN_PROGRESS),

    /** Work is finished. */
    COMPLETE(TaskStatus.IN_PROGRESS, TaskStatus.DONE),

    /**
     * Work stops without being finished, and the task goes back to the queue --
     * with its agent, which can then be changed. The recovery path for an
     * agent that stopped working (ADR-010 D3), now that "working" is a state.
     */
    STOP(TaskStatus.IN_PROGRESS, TaskStatus.OPEN),

    /** Finished work turns out not to be. Back to the queue, like {@link #STOP}. */
    REOPEN(TaskStatus.DONE, TaskStatus.OPEN);

    private final TaskStatus from;
    private final TaskStatus to;

    TaskTransition(TaskStatus from, TaskStatus to) {
        this.from = from;
        this.to = to;
    }

    public TaskStatus from() {
        return from;
    }

    public TaskStatus to() {
        return to;
    }

    /**
     * Whether this edge needs the task's agent to be present and active.
     *
     * <p>Only {@link #START}: {@code IN_PROGRESS} means "somebody is working on it
     * now" (ADR-011), so entering it needs a somebody who can. Leaving it does not
     * -- an agent switched off mid-work must not trap the task (ADR-010 D3), and
     * {@link #STOP} and {@link #COMPLETE} are how the operator gets it out.
     */
    public boolean requiresActiveAgent() {
        return this == START;
    }
}
