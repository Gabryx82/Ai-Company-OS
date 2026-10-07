package com.aicompany.backend.terminal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-029 §5: the modes a fresh xterm.js is put back in when a console attaches again. */
class TerminalModesTest {

    private static void feed(TerminalModes modes, String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        modes.scan(bytes, bytes.length);
    }

    private static String restored(TerminalModes modes) {
        return new String(modes.restore(), StandardCharsets.US_ASCII).replace("\u001b", "ESC");
    }

    @Test
    void claudeCodesFullScreenViewIsRestoredWithItsMouse() {
        TerminalModes modes = new TerminalModes();
        // What Claude Code 2.1 sends through ConPTY when it starts (measured on 2026-10-07).
        feed(modes, "\u001b[?1004h\u001b[?9001h\u001b[?25l\u001b[?2004h\u001b[?1004l\u001b[?2004l"
                + "\u001b[?1049h\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1006h\u001b[?2004h\u001b[?25h");

        assertThat(modes.inAlternateScreen()).isTrue();
        assertThat(restored(modes)).isEqualTo(
                "ESC[?1049hESC[?25hESC[?1000hESC[?1002hESC[?1003hESC[?1004lESC[?1006hESC[?2004h");
    }

    @Test
    void aSequenceSplitAcrossReadsIsSeenWhole() {
        TerminalModes modes = new TerminalModes();
        feed(modes, "text \u001b[");
        feed(modes, "?10");
        feed(modes, "49h more text");

        assertThat(modes.inAlternateScreen()).isTrue();
    }

    @Test
    void severalModesInOneSequenceAndTheLastWordWins() {
        TerminalModes modes = new TerminalModes();
        feed(modes, "\u001b[?1000;1006h");
        feed(modes, "\u001b[?1049h\u001b[?1049l");

        assertThat(modes.inAlternateScreen()).isFalse();
        assertThat(restored(modes)).isEqualTo("ESC[?1049lESC[?1000hESC[?1006h");
    }

    @Test
    void whatIsNotAPrivateModeIsIgnored() {
        TerminalModes modes = new TerminalModes();
        feed(modes, "\u001b[2J\u001b[1;1H\u001b[38;2;255;193;7m\u001b[?9001h\u001b[?u plain ? h l");

        assertThat(modes.restore()).isEmpty();
    }
}
