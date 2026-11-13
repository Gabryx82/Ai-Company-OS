package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * PHASE 37 (ADR-043): one tool that receives the agents made in AI Company OS, in the
 * shape it has for an agent. A connector writes only what it made -- an identifier
 * that begins with {@link AgentProfile#PREFIX}, or a file that carries its marker --
 * and says so, rather than guess, when the tool is not there or not configured.
 */
public interface AgentSyncConnector {

    /** Whether the tool can be written to from here. */
    enum Availability {
        /** There, and the connector can publish. */
        AVAILABLE,
        /** Not on this machine. */
        NOT_INSTALLED,
        /** There, but something the operator has to provide is missing: a key, a path. */
        NOT_CONFIGURED,
        /** Configured, and it does not answer. */
        UNREACHABLE
    }

    /**
     * @param detail what was found, in the operator's words
     * @param hint   what to do when the tool is not {@link Availability#AVAILABLE}; null otherwise
     */
    record Status(Availability availability, String detail, @Nullable String hint) {
        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        public static Status available(String detail) {
            return new Status(Availability.AVAILABLE, detail, null);
        }
    }

    /** An agent as the tool has it. {@code ours} when its identifier or its marker says AI Company OS made it. */
    record ExternalAgent(String externalId, String name, @Nullable String description, @Nullable String instructions,
                         boolean ours) {
    }

    /**
     * @param externalId how the tool knows the agent
     * @param changed    false when the tool already held exactly this
     * @param notes      what the operator should know: what was left as it was, what takes a restart
     */
    record Published(String externalId, String fingerprint, boolean changed, List<String> notes) {
        public Published {
            notes = List.copyOf(notes);
        }
    }

    /** The key of the tool: lower-case, as it is stored and as the API names it. */
    String key();

    String name();

    /** What an agent becomes in the tool, in a few words: «un agente con il suo spazio di lavoro». */
    String shape();

    Status status();

    /**
     * Why this agent cannot be published to the tool -- a model the tool cannot run --
     * or empty when it can.
     */
    Optional<String> notApplicable(AgentProfile agent);

    /** What {@link #publish} would write for this agent, as a fingerprint. */
    String fingerprint(AgentProfile agent);

    /**
     * Creates the agent in the tool, or brings the one it has up to date.
     *
     * @param externalId the identifier a previous publication got, or null for the first
     */
    Published publish(AgentProfile agent, @Nullable String externalId);

    /** The tool's agents, its own and ours; empty when the tool cannot be asked. */
    List<ExternalAgent> list();

    /**
     * ADR-046: the tool's agents as they are known now, for a page that must not wait. A tool that answers at
     * once is simply asked; one that takes long (OpenClaw's command: 12-60 s) answers with its last reading
     * and renews it in the background.
     *
     * @param at         when the agents were read; null before the first reading finished
     * @param refreshing a reading is under way: a look in a few seconds has a newer one
     * @param problem    why the last reading failed, when it did
     */
    record Reading(List<ExternalAgent> agents, java.time.@Nullable Instant at, boolean refreshing, @Nullable String problem) {
        public Reading {
            agents = List.copyOf(agents);
        }

        public boolean known() {
            return at != null;
        }
    }

    default Reading reading() {
        return new Reading(list(), java.time.Instant.now(), false, null);
    }

    /** Whether the tool has agents of its own that can be brought into AI Company OS. */
    default boolean imports() {
        return false;
    }

    /** Removes what {@link #publish} made. Never called for an agent the tool had before. */
    void remove(String externalId);
}
