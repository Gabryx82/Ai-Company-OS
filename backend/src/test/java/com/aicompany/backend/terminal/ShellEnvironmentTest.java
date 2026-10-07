package com.aicompany.backend.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-029 §5: a shell of the console gets the operator's environment, not the backend's parent's. */
class ShellEnvironmentTest {

    @Test
    void aParentClaudeCodeSessionsMarkersDoNotReachTheShells() {
        Map<String, String> environment = ShellEnvironment.withoutSessionMarkers(Map.of(
                "CLAUDECODE", "1",
                "CLAUDE_CODE_CHILD_SESSION", "1",
                "claude_code_messaging_token", "a-token",
                "CLAUDE_CODE_USE_BEDROCK", "1",
                "Path", "C:\\Windows"));

        // Configuration stays; what only says "you are inside another session" goes.
        assertThat(environment).containsOnlyKeys("CLAUDE_CODE_USE_BEDROCK", "Path");
    }

    @Test
    void theShellsAreToldTheyRunInAnXterm() {
        assertThat(ShellEnvironment.forShells(Map.of("TERM", "dumb"))).containsEntry("TERM", "xterm-256color");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheEnvironmentIsTheUsersOwnNotTheBackends() {
        Map<String, String> environment = ShellEnvironment.forShells(Map.of("AICOS_ONLY_IN_THE_BACKEND", "x"));

        assertThat(environment).doesNotContainKey("AICOS_ONLY_IN_THE_BACKEND");
        assertThat(environment.keySet()).anyMatch(name -> name.equalsIgnoreCase("Path"));
        assertThat(environment.keySet()).anyMatch(name -> name.equalsIgnoreCase("SystemRoot"));
        assertThat(environment.keySet()).anyMatch(name -> name.equalsIgnoreCase("USERPROFILE"));
    }
}
