package com.aicompany.backend.usage.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static com.aicompany.backend.usage.local.ProtoWire.Field;
import static com.aicompany.backend.usage.local.ProtoWire.all;
import static com.aicompany.backend.usage.local.ProtoWire.parse;

/**
 * The model quotas an Antigravity app received from Google, read from the state
 * it keeps on disk ({@code <user-data>/User/globalStorage/state.vscdb}, a SQLite
 * database, key {@value #KEY}). Antigravity and Antigravity IDE each keep their own.
 *
 * <p>The value is a protobuf (base64) whose layout is observed, not published: the
 * user status carries, in field 33, one entry per model -- its label (1) and its
 * quota (15): the fraction that remains (1, a float that protobuf omits when it is
 * zero) and when it resets (2, a timestamp). The user status is looked for, not
 * reached by a fixed path, so a change in the wrapper does not hide it; a change in
 * the entries makes the source report no data rather than wrong numbers.
 *
 * <p>Read-only, and only labels and numbers leave this class: the same value holds
 * the account's name and e-mail, which are never read out.
 */
public class AntigravityQuota {

    private static final Logger log = LoggerFactory.getLogger(AntigravityQuota.class);

    static final String KEY = "antigravityUnifiedStateSync.userStatus";
    private static final int USER_STATUS_MODELS = 33;
    private static final int MODEL_CONFIG = 1;
    private static final int LABEL = 1;
    private static final int QUOTA = 15;
    private static final int REMAINING_FRACTION = 1;
    private static final int RESET_TIME = 2;
    private static final int SECONDS = 1;
    private static final int SEARCH_DEPTH = 5;

    public record ModelQuota(String model, double usedPercent, Instant resetsAt) {
    }

    /** {@code observedAt}: when the app last saved its state -- the quotas are at most that old. */
    public record Snapshot(Instant observedAt, List<ModelQuota> models) {
    }

    private final String subject;
    private final Path userData;

    public AntigravityQuota(String subject, Path userData) {
        this.subject = subject;
        this.userData = userData;
    }

    /** The quota plan subject this app's figures belong to. */
    public String subject() {
        return subject;
    }

    public Optional<Snapshot> latest() {
        Path database = userData.resolve("User").resolve("globalStorage").resolve("state.vscdb");
        if (!Files.isRegularFile(database)) {
            return Optional.empty();
        }
        Optional<String> value = read(database);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            Instant savedAt = Files.getLastModifiedTime(database).toInstant();
            return Optional.of(new Snapshot(savedAt, models(value.get())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The models and their quotas in a stored user status; empty when the layout is not recognised. */
    static List<ModelQuota> models(String storedValue) {
        Optional<List<Field>> status = decode(storedValue.strip().getBytes(StandardCharsets.US_ASCII))
                .flatMap(message -> findUserStatus(message, SEARCH_DEPTH));
        if (status.isEmpty()) {
            return List.of();
        }
        List<ModelQuota> models = new ArrayList<>();
        for (Field group : all(status.get(), USER_STATUS_MODELS)) {
            if (!group.isMessage()) {
                continue;
            }
            for (Field config : all(parse(group.bytes()), MODEL_CONFIG)) {
                if (!config.isMessage()) {
                    continue;
                }
                modelQuota(parse(config.bytes())).ifPresent(models::add);
            }
        }
        return models;
    }

    private static Optional<ModelQuota> modelQuota(List<Field> config) {
        Optional<String> label = all(config, LABEL).stream()
                .filter(f -> f.wireType() == ProtoWire.LENGTH_DELIMITED)
                .map(f -> new String(f.bytes(), StandardCharsets.UTF_8))
                .findFirst();
        Optional<Field> quota = all(config, QUOTA).stream().filter(Field::isMessage).findFirst();
        if (label.isEmpty() || quota.isEmpty()) {
            return Optional.empty();
        }
        List<Field> fields = parse(quota.get().bytes());
        double remaining = all(fields, REMAINING_FRACTION).stream()
                .filter(f -> f.wireType() == ProtoWire.FIXED32)
                .mapToDouble(Field::fixed32AsFloat)
                .findFirst()
                .orElse(0.0);
        Instant resetsAt = all(fields, RESET_TIME).stream()
                .filter(Field::isMessage)
                .flatMap(f -> all(parse(f.bytes()), SECONDS).stream())
                .filter(f -> f.wireType() == ProtoWire.VARINT && f.varint() > 0)
                .map(f -> Instant.ofEpochSecond(f.varint()))
                .findFirst()
                .orElse(null);
        double used = Math.round(Math.max(0, Math.min(1, 1 - remaining)) * 1000) / 10.0;
        return Optional.of(new ModelQuota(label.get(), used, resetsAt));
    }

    /** The message that lists the models: it has field 33 whose entries carry a label and a quota. */
    private static Optional<List<Field>> findUserStatus(List<Field> message, int depth) {
        if (listsModels(message)) {
            return Optional.of(message);
        }
        if (depth == 0) {
            return Optional.empty();
        }
        for (Field field : message) {
            if (field.wireType() != ProtoWire.LENGTH_DELIMITED) {
                continue;
            }
            Optional<List<Field>> found = decode(field.bytes()).flatMap(inner -> findUserStatus(inner, depth - 1));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static boolean listsModels(List<Field> message) {
        for (Field group : all(message, USER_STATUS_MODELS)) {
            if (!group.isMessage()) {
                continue;
            }
            for (Field config : all(parse(group.bytes()), MODEL_CONFIG)) {
                if (config.isMessage() && all(parse(config.bytes()), QUOTA).stream().anyMatch(Field::isMessage)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Bytes as a message: directly, or base64 text that decodes to one. */
    private static Optional<List<Field>> decode(byte[] bytes) {
        if (looksLikeBase64(bytes)) {
            try {
                String text = new String(bytes, StandardCharsets.US_ASCII);
                byte[] decoded = text.indexOf('-') >= 0 || text.indexOf('_') >= 0
                        ? Base64.getUrlDecoder().decode(text) : Base64.getDecoder().decode(text);
                return Optional.of(parse(decoded));
            } catch (IllegalArgumentException notBase64OrNotAMessage) {
                // fall through: the text may still be a message by itself
            }
        }
        try {
            return Optional.of(parse(bytes));
        } catch (IllegalArgumentException notAMessage) {
            return Optional.empty();
        }
    }

    private static boolean looksLikeBase64(byte[] bytes) {
        if (bytes.length < 16) {
            return false;
        }
        for (byte b : bytes) {
            boolean ok = b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9'
                    || b == '+' || b == '/' || b == '=' || b == '-' || b == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private Optional<String> read(Path database) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(2000);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(),
                config.toProperties());
             PreparedStatement select = connection.prepareStatement("SELECT value FROM ItemTable WHERE key = ?")) {
            select.setString(1, KEY);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                byte[] value = rows.getBytes(1);
                return value == null ? Optional.empty() : Optional.of(new String(value, StandardCharsets.US_ASCII));
            }
        } catch (SQLException e) {
            log.debug("Antigravity state of '{}' unreadable: {}", subject, e.toString());
            return Optional.empty();
        }
    }
}
