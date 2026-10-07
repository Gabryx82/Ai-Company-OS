package com.aicompany.backend.terminal;

/**
 * The latest output of a shell, kept so that a console attaching again sees what
 * it missed (ADR-029 §5). A ring of {@link #CAPACITY} bytes: when it is full the
 * oldest bytes go, and when that cut a line the replay starts at the next one,
 * so that it never begins halfway through an escape sequence or a UTF-8
 * character.
 */
final class OutputHistory {

    static final int CAPACITY = 512 * 1024;

    private final byte[] ring;
    private int start;
    private int size;
    private boolean trimmed;
    private byte lastDropped;

    OutputHistory() {
        this(CAPACITY);
    }

    OutputHistory(int capacity) {
        this.ring = new byte[capacity];
    }

    void append(byte[] bytes, int length) {
        int from = 0;
        if (length > ring.length) {
            from = length - ring.length;
            trimmed = true;
            lastDropped = bytes[from - 1];
            start = 0;
            size = 0;
        }
        for (int i = from; i < length; i++) {
            if (size == ring.length) {
                lastDropped = ring[start];
                start = (start + 1) % ring.length;
                size--;
                trimmed = true;
            }
            ring[(start + size) % ring.length] = bytes[i];
            size++;
        }
    }

    /** What to send to a fresh terminal: everything kept, from a whole line when the start was cut. */
    byte[] replay() {
        int skip = 0;
        if (trimmed && lastDropped != '\n') {
            while (skip < size && ring[(start + skip) % ring.length] != '\n') {
                skip++;
            }
            skip = Math.min(size, skip + 1);
        }
        byte[] out = new byte[size - skip];
        for (int i = 0; i < out.length; i++) {
            out[i] = ring[(start + skip + i) % ring.length];
        }
        return out;
    }
}
