package com.aicompany.backend.usage.local;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The protobuf wire format, read without a schema: field number, wire type and
 * the raw value. Enough to walk a message whose layout was observed, not
 * declared -- which is the case of the state Antigravity keeps on disk.
 *
 * <p>Anything malformed is an {@link IllegalArgumentException}: a caller that
 * probes bytes to see whether they are a message relies on it.
 */
final class ProtoWire {

    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    record Field(int number, int wireType, long varint, byte[] bytes) {

        float fixed32AsFloat() {
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        }

        boolean isMessage() {
            if (wireType != LENGTH_DELIMITED) {
                return false;
            }
            try {
                parse(bytes);
                return true;
            } catch (IllegalArgumentException notAMessage) {
                return false;
            }
        }
    }

    private ProtoWire() {
    }

    static List<Field> parse(byte[] buffer) {
        List<Field> fields = new ArrayList<>();
        int[] at = {0};
        while (at[0] < buffer.length) {
            long key = varint(buffer, at);
            int number = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            if (number <= 0) {
                throw new IllegalArgumentException("field number " + number);
            }
            switch (wireType) {
                case VARINT -> fields.add(new Field(number, wireType, varint(buffer, at), null));
                case FIXED64 -> fields.add(new Field(number, wireType, 0, take(buffer, at, 8)));
                case FIXED32 -> fields.add(new Field(number, wireType, 0, take(buffer, at, 4)));
                case LENGTH_DELIMITED -> {
                    long length = varint(buffer, at);
                    if (length < 0 || length > buffer.length - at[0]) {
                        throw new IllegalArgumentException("length " + length + " past the end");
                    }
                    fields.add(new Field(number, wireType, 0, take(buffer, at, (int) length)));
                }
                default -> throw new IllegalArgumentException("wire type " + wireType);
            }
        }
        return fields;
    }

    static List<Field> all(List<Field> fields, int number) {
        return fields.stream().filter(f -> f.number() == number).toList();
    }

    private static long varint(byte[] buffer, int[] at) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (at[0] >= buffer.length) {
                throw new IllegalArgumentException("varint past the end");
            }
            byte b = buffer[at[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varint longer than 64 bits");
    }

    private static byte[] take(byte[] buffer, int[] at, int length) {
        if (length > buffer.length - at[0]) {
            throw new IllegalArgumentException("value past the end");
        }
        byte[] value = Arrays.copyOfRange(buffer, at[0], at[0] + length);
        at[0] += length;
        return value;
    }
}
