package com.aicompany.backend.usage.local;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;

/**
 * An Antigravity state database shaped like the real one (observed on 2026-10-11):
 * {@code ItemTable(key, value)}, the user status under {@value AntigravityQuota#KEY} as
 * base64 of a wrapper whose innermost string is base64 of the user status -- with the
 * account's name and e-mail, so a test can prove they never come out.
 */
public final class AntigravityStateFixture {

    public static final String NAME = "Ada Lovelace";
    public static final String EMAIL = "ada@example.org";

    private final ByteArrayOutputStream models = new ByteArrayOutputStream();

    /** A model with a quota; {@code remaining} null leaves the float out, as protobuf does for zero. */
    public AntigravityStateFixture model(String label, Float remaining, Instant resetsAt) {
        byte[] quota = concat(remaining == null ? new byte[0] : fixed32(1, remaining),
                resetsAt == null ? new byte[0] : message(2, varint(1, resetsAt.getEpochSecond())));
        byte[] config = concat(string(1, label), message(2, varint(1, 1318)), varint(5, 1), message(15, quota),
                message(18, concat(string(1, "image/png"), varint(2, 1))));
        models.writeBytes(message(1, config));
        return this;
    }

    /** A model the app lists without a quota: it is skipped. */
    public AntigravityStateFixture modelWithoutQuota(String label) {
        models.writeBytes(message(1, concat(string(1, label), varint(5, 1))));
        return this;
    }

    /** The value as Antigravity stores it. */
    public String storedValue() {
        byte[] status = concat(varint(2, 1), string(3, NAME), string(7, EMAIL),
                message(33, concat(models.toByteArray(), message(2, string(1, "Recommended")))));
        byte[] wrapper = message(1, concat(string(1, "userStatusSentinelKey"),
                message(2, string(1, Base64.getEncoder().encodeToString(status)))));
        return Base64.getEncoder().encodeToString(wrapper);
    }

    /** {@code <userData>/User/globalStorage/state.vscdb} holding {@code value} under the user status key. */
    public static void writeDatabase(Path userData, String value) throws Exception {
        Path folder = Files.createDirectories(userData.resolve("User").resolve("globalStorage"));
        Path database = folder.resolve("state.vscdb");
        Files.deleteIfExists(database);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement create = c.createStatement()) {
            create.execute("CREATE TABLE ItemTable (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)");
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO ItemTable VALUES (?, ?)")) {
                insert.setString(1, "languageStatus.interactCount");
                insert.setString(2, "1");
                insert.executeUpdate();
                insert.setString(1, AntigravityQuota.KEY);
                insert.setString(2, value);
                insert.executeUpdate();
            }
        }
    }

    // --- protobuf wire format ---------------------------------------------------

    static byte[] varint(int field, long value) {
        return concat(key(field, 0), rawVarint(value));
    }

    static byte[] fixed32(int field, float value) {
        return concat(key(field, 5), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
    }

    static byte[] string(int field, String text) {
        return message(field, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] message(int field, byte[] body) {
        return concat(key(field, 2), rawVarint(body.length), body);
    }

    private static byte[] key(int field, int wireType) {
        return rawVarint(((long) field << 3) | wireType);
    }

    private static byte[] rawVarint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            byte b = (byte) (value & 0x7F);
            value >>>= 7;
            out.write(value == 0 ? b : b | 0x80);
        } while (value != 0);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
