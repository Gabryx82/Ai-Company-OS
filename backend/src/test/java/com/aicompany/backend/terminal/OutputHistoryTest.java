package com.aicompany.backend.terminal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-029 §5: what a console attaching again is sent of the output it missed. */
class OutputHistoryTest {

    private static void append(OutputHistory history, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        history.append(bytes, bytes.length);
    }

    private static String replay(OutputHistory history) {
        return new String(history.replay(), StandardCharsets.UTF_8);
    }

    @Test
    void untilItIsFullEverythingIsReplayed() {
        OutputHistory history = new OutputHistory(64);
        append(history, "PS C:\\> dir\r\n");
        append(history, "riga 1\r\n");

        assertThat(replay(history)).isEqualTo("PS C:\\> dir\r\nriga 1\r\n");
    }

    @Test
    void whenTheStartIsCutMidLineTheReplayBeginsOnTheNextLine() {
        OutputHistory history = new OutputHistory(20);
        append(history, "first line\r\nsecond \u001b[1mline\r\nthird\r\n");

        // "first line" and part of the second went; the half line, with its half sequence, is not sent.
        assertThat(replay(history)).isEqualTo("third\r\n");
    }

    @Test
    void whenTheCutFallsBetweenLinesNothingMoreIsLost() {
        OutputHistory history = new OutputHistory(24);
        append(history, "first line\r\nsecond \u001b[1mline\r\nthird\r\n");

        assertThat(replay(history)).isEqualTo("second \u001b[1mline\r\nthird\r\n");
    }

    @Test
    void aChunkLargerThanTheRingKeepsItsEnd() {
        OutputHistory history = new OutputHistory(16);
        append(history, "aaaaaaaaaaaaaaaaaaaaaaaa\nlast line\n");

        assertThat(replay(history)).isEqualTo("last line\n");
    }
}
