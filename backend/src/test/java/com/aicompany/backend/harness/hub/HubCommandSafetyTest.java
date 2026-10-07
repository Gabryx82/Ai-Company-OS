package com.aicompany.backend.harness.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 31 (ADR-036 §6b): what reaches Claude Code's and Codex's CLIs is one
 * argument per value -- never something a command line would read as syntax.
 * No Spring; nothing is started.
 */
class HubCommandSafetyTest {

    @Test
    void aCliRunThroughCmdNeverGetsAValueTheCommandLineWouldInterpret(@TempDir Path home) throws IOException {
        Path cmd = Files.writeString(home.resolve("claude.cmd"), "@echo off\r\n");
        ProcessHubCommands clis = new ProcessHubCommands(home.toString(), home.toString(), cmd.toString(), "");

        for (String value : List.of("https://mcp.example.org/mcp&rem", "KEY=a|rem", "KEY=%PATH%", "x>NUL", "a^b")) {
            assertThatThrownBy(() -> clis.run(HubCommands.Tool.CLAUDE, List.of("mcp", "add", value), Duration.ofSeconds(5)))
                    .isInstanceOf(HubProblemException.class).hasMessageContaining("prompt dei comandi");
        }
        // Quotes and line breaks are refused whatever the CLI is.
        assertThatThrownBy(() -> ProcessHubCommands.requireSafe(List.of("Authorization: Bearer \"x\""), false))
                .isInstanceOf(HubProblemException.class);
        assertThatThrownBy(() -> ProcessHubCommands.requireSafe(List.of("KEY=a\nb"), false))
                .isInstanceOf(HubProblemException.class);
        // A native executable gets them as they are: one argument each.
        assertThatCode(() -> ProcessHubCommands.requireSafe(List.of("KEY=a&b|c%d", "Authorization: Bearer sk-1"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void aConnectorGetsANameTheApplicationsAcceptAndThatSaysWhatItIs() {
        assertThat(ExtensionMarketplaces.shortName("io.github.acme/files-mcp")).isEqualTo("files-mcp");
        assertThat(ExtensionMarketplaces.shortName("com.notion/mcp")).isEqualTo("notion");
        assertThat(ExtensionMarketplaces.shortName("io.github.someone/My Server!")).isEqualTo("my-server");
    }

    @Test
    void aRemoteConnectorIsOnlyAnHttpsAddressWithNothingToFillInOrInterpret() {
        assertThat(ExtensionMarketplaces.REMOTE_URL.matcher("https://mcp.notion.com/mcp").matches()).isTrue();
        assertThat(ExtensionMarketplaces.REMOTE_URL.matcher("https://api.example.org:8443/v1/mcp").matches()).isTrue();
        for (String url : List.of("http://mcp.example.org/mcp", "https://{tenant}.example.org/mcp", "https://x.org/mcp?key=1&rem",
                "https://x.org/a b", "https://x.org/\"q\"", "https://x.org/%25", "https://user@x.org/mcp")) {
            assertThat(ExtensionMarketplaces.REMOTE_URL.matcher(url).matches()).as(url).isFalse();
        }
    }
}
