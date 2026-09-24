package com.aicompany.backend.usage;

import com.aicompany.backend.usage.local.ClaudeCodeUsage;
import com.aicompany.backend.usage.local.CodexRateLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two local sources read the numbers their tools wrote, and nothing else.
 * Fixtures reproduce the real line shapes observed on 2026-09-24 (field names
 * and nesting), with invented values and no content.
 */
class LocalUsageSourcesTest {

    @TempDir
    Path home;

    // --- Codex -----------------------------------------------------------------

    private static String codexLine(String timestamp, double primary, long primaryReset, double secondary,
                                    long secondaryReset) {
        return """
                {"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":\
                {"limit_id":"codex","primary":{"used_percent":%s,"window_minutes":300,"resets_at":%d},\
                "secondary":{"used_percent":%s,"window_minutes":10080,"resets_at":%d},"plan_type":"plus"}}}"""
                .formatted(timestamp, primary, primaryReset, secondary, secondaryReset);
    }

    @Test
    void codexGivesItsLastRecordedRateLimitsWithTheirResets() throws IOException {
        Path day = Files.createDirectories(home.resolve("sessions/2026/09/24"));
        Files.writeString(day.resolve("rollout-a.jsonl"), String.join("\n",
                "{\"timestamp\":\"2026-09-24T08:00:00Z\",\"type\":\"session_meta\",\"payload\":{}}",
                codexLine("2026-09-24T09:00:00Z", 10.0, 1_790_000_000L, 3.0, 1_790_500_000L),
                codexLine("2026-09-24T10:00:00Z", 42.5, 1_790_000_000L, 7.0, 1_790_500_000L)));

        CodexRateLimits.Snapshot snapshot = new CodexRateLimits(home).latest().orElseThrow();

        assertThat(snapshot.observedAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
        assertThat(snapshot.planType()).isEqualTo("plus");
        assertThat(snapshot.windows()).containsExactly(
                new CodexRateLimits.Window(42.5, 300, Instant.ofEpochSecond(1_790_000_000L)),
                new CodexRateLimits.Window(7.0, 10_080, Instant.ofEpochSecond(1_790_500_000L)));
    }

    @Test
    void noCodexHomeIsNoDataNotAnError() {
        assertThat(new CodexRateLimits(home.resolve("absent")).latest()).isEmpty();
    }

    // --- Claude Code -----------------------------------------------------------

    private static String claudeLine(String timestamp, String messageId, String requestId, long in, long out,
                                     long cacheWrite, long cacheRead) {
        return """
                {"type":"assistant","timestamp":"%s","requestId":"%s","message":{"id":"%s","model":"claude-opus-5-5",\
                "role":"assistant","content":[{"type":"text","text":"SECRET CONTENT"}],"usage":{"input_tokens":%d,\
                "output_tokens":%d,"cache_creation_input_tokens":%d,"cache_read_input_tokens":%d}}}"""
                .formatted(timestamp, requestId, messageId, in, out, cacheWrite, cacheRead);
    }

    @Test
    void claudeCodeTokensAreCountedOncePerMessageAndOnlyNumbersAreRead() throws IOException {
        Path project = Files.createDirectories(home.resolve("projects/C--demo"));
        Files.writeString(project.resolve("s.jsonl"), String.join("\n",
                "{\"type\":\"user\",\"timestamp\":\"2026-09-24T09:59:00Z\",\"message\":{\"content\":\"hi\"}}",
                claudeLine("2026-09-24T10:00:00Z", "msg_1", "req_1", 10, 100, 1000, 5000),
                // the same message again, as Claude Code writes one line per content block
                claudeLine("2026-09-24T10:00:01Z", "msg_1", "req_1", 10, 100, 1000, 5000),
                claudeLine("2026-09-24T10:30:00Z", "msg_2", "req_2", 1, 20, 0, 300),
                "{torn line"));

        List<ClaudeCodeUsage.Entry> entries =
                new ClaudeCodeUsage(home).entriesSince(Instant.parse("2026-09-20T00:00:00Z"));

        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst().total()).isEqualTo(10 + 100 + 1000 + 5000);
        assertThat(entries).extracting(ClaudeCodeUsage.Entry::model).containsOnly("claude-opus-5-5");
    }

    @Test
    void theFiveHourWindowOpensAtTheHourOfItsFirstMessageAndClosesFiveHoursLater() {
        List<ClaudeCodeUsage.Entry> entries = List.of(
                entry("2026-09-24T02:10:00Z"),   // an earlier window, closed at 07:00
                entry("2026-09-24T09:47:00Z"),   // opens 09:00 → 14:00
                entry("2026-09-24T13:59:00Z"));  // still inside it

        ClaudeCodeUsage.Block block =
                ClaudeCodeUsage.activeBlock(entries, Instant.parse("2026-09-24T12:00:00Z")).orElseThrow();

        assertThat(block.start()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
        assertThat(block.end()).isEqualTo(Instant.parse("2026-09-24T14:00:00Z"));
        assertThat(block.entries()).hasSize(2);

        assertThat(ClaudeCodeUsage.activeBlock(entries, Instant.parse("2026-09-24T14:00:00Z")))
                .as("at the end of the window no window is open: the next message opens one")
                .isEmpty();
    }

    private static ClaudeCodeUsage.Entry entry(String at) {
        return new ClaudeCodeUsage.Entry(Instant.parse(at), "m", 1, 1, 0, 0);
    }
}
