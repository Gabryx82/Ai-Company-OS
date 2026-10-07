package com.aicompany.backend.harness.hub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * The note the Hub leaves inside every copy of a library skill it installs in an
 * application ({@code .aicos-hub.json}, ADR-036 §5): which library entry it is,
 * and the fingerprints of both sides when it was written. The applications skip
 * hidden files; the Hub reads it back to tell a copy that is current from one the
 * library has moved past, and from one somebody changed inside the application.
 *
 * <p>A folder without it was not written by the Hub, and the Hub never overwrites
 * it without the operator saying so.
 */
public record HubMarker(String key, String library, String copy, Instant deployedAt) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static Optional<HubMarker> read(Path folder) {
        Path file = folder.resolve(Fingerprints.MARKER);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String key = text(node, "key");
            String library = text(node, "library");
            String copy = text(node, "copy");
            if (key == null || library == null || copy == null) {
                return Optional.empty();
            }
            String at = text(node, "deployedAt");
            return Optional.of(new HubMarker(key, library, copy, at == null ? null : Instant.parse(at)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void write(Path folder) throws IOException {
        Files.write(folder.resolve(Fingerprints.MARKER), json());
    }

    public byte[] json() {
        return JSON.writeValueAsString(JSON.createObjectNode()
                .put("key", key).put("library", library).put("copy", copy)
                .put("deployedAt", deployedAt.toString())
                .put("note", "Scritto dal Knowledge Hub di AI Company OS: non modificare.")).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
