package com.aicompany.backend.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terminal modes a program has switched on or off, read from its output
 * (ADR-029 §5): the alternate screen, the mouse reports, bracketed paste, focus
 * events, cursor keys and visibility. A console that attaches again starts from
 * a fresh xterm.js, so it is told these before the program repaints -- without
 * them Claude Code's full-screen view would be drawn in the normal buffer and the
 * wheel would no longer reach it.
 *
 * <p>Only {@code ESC [ ? n ; n … h|l} is read, byte by byte, so a sequence split
 * across two reads is still seen whole.
 */
final class TerminalModes {

    /** Alternate screen: any of the three spellings; restored as 1049. */
    private static final List<Integer> ALTERNATE_SCREEN = List.of(1049, 1047, 47);
    private static final List<Integer> OTHERS = List.of(1, 25, 1000, 1002, 1003, 1004, 1005, 1006, 1015, 2004);

    private enum State { TEXT, ESCAPE, CSI, PRIVATE }

    private final Map<Integer, Boolean> modes = new LinkedHashMap<>();
    private State state = State.TEXT;
    private final StringBuilder parameters = new StringBuilder();

    void scan(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            byte b = bytes[i];
            switch (state) {
                case TEXT -> {
                    if (b == 0x1b) {
                        state = State.ESCAPE;
                    }
                }
                case ESCAPE -> state = b == '[' ? State.CSI : b == 0x1b ? State.ESCAPE : State.TEXT;
                case CSI -> {
                    if (b == '?') {
                        parameters.setLength(0);
                        state = State.PRIVATE;
                    } else {
                        state = b == 0x1b ? State.ESCAPE : State.TEXT;
                    }
                }
                case PRIVATE -> {
                    if ((b >= '0' && b <= '9') || b == ';') {
                        if (parameters.length() < 64) {
                            parameters.append((char) b);
                        }
                    } else {
                        if (b == 'h' || b == 'l') {
                            apply(b == 'h');
                        }
                        state = b == 0x1b ? State.ESCAPE : State.TEXT;
                    }
                }
            }
        }
    }

    private void apply(boolean on) {
        for (String parameter : parameters.toString().split(";")) {
            if (parameter.isEmpty() || parameter.length() > 5) {
                continue;
            }
            int mode = Integer.parseInt(parameter);
            if (ALTERNATE_SCREEN.contains(mode) || OTHERS.contains(mode)) {
                modes.put(mode, on);
            }
        }
    }

    boolean inAlternateScreen() {
        return ALTERNATE_SCREEN.stream().anyMatch(mode -> Boolean.TRUE.equals(modes.get(mode)));
    }

    /** The sequences that put a fresh terminal in the modes last seen; empty when none was seen. */
    byte[] restore() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (ALTERNATE_SCREEN.stream().anyMatch(modes::containsKey)) {
            write(out, 1049, inAlternateScreen());
        }
        for (int mode : OTHERS) {
            Boolean on = modes.get(mode);
            if (on != null) {
                write(out, mode, on);
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, int mode, boolean on) {
        out.writeBytes(("\u001b[?" + mode + (on ? "h" : "l")).getBytes(StandardCharsets.US_ASCII));
    }
}
