package com.aicompany.backend.harness.hub;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The applications' own command-line tools, as the Hub uses them to install
 * plugins and connectors (ADR-036 §6b): {@code claude plugin install},
 * {@code claude mcp add}, {@code codex plugin add}, {@code codex mcp add}. The
 * applications keep their configuration; the Hub never writes it behind their
 * back when a command exists for it. An interface so that tests never run a CLI.
 */
public interface HubCommands {

    /** The tools the Hub may run. Nothing else is ever executed. */
    enum Tool { CLAUDE, CODEX }

    record Result(int exitCode, String output, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** Where the tool is on this machine, if it is. */
    Optional<Path> executable(Tool tool);

    /**
     * Runs the tool with these arguments -- as an argument list, never through a
     * shell -- and returns its exit code and output (stdout and stderr, capped).
     */
    Result run(Tool tool, List<String> arguments, Duration timeout);
}
