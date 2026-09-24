package com.aicompany.backend.usage.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The rate limits Codex itself recorded, read from its session logs
 * ({@code <codex-home>/sessions/YYYY/MM/DD/*.jsonl}).
 *
 * <p>Codex writes a {@code token_count} event with {@code rate_limits}: for each
 * window the percentage used, its length in minutes and the epoch second it
 * resets. That is a measurement taken by the vendor's own client, which is why
 * it is trusted here -- and only the numbers are read: no line's content leaves
 * this class.
 */
public class CodexRateLimits {

    private static final Logger log = LoggerFactory.getLogger(CodexRateLimits.class);

    public record Window(double usedPercent, int windowMinutes, Instant resetsAt) {
    }

    public record Snapshot(Instant observedAt, List<Window> windows, String planType) {
    }

    private final Path codexHome;
    private final JsonMapper json = JsonMapper.builder().build();

    public CodexRateLimits(Path codexHome) {
        this.codexHome = codexHome;
    }

    /** The most recent observation across the newest session files, if any exists. */
    public Optional<Snapshot> latest() {
        Path sessions = codexHome.resolve("sessions");
        if (!Files.isDirectory(sessions)) {
            return Optional.empty();
        }
        List<Path> newest;
        try (Stream<Path> files = Files.walk(sessions, 5)) {
            newest = files.filter(path -> path.toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(CodexRateLimits::modified).reversed())
                    .limit(5)
                    .toList();
        } catch (IOException e) {
            log.debug("Codex sessions unreadable: {}", e.toString());
            return Optional.empty();
        }
        return newest.stream()
                .map(this::lastSnapshotIn)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(Snapshot::observedAt));
    }

    private Optional<Snapshot> lastSnapshotIn(Path file) {
        String last = null;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"rate_limits\"")) {
                    last = line;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (last == null) {
            return Optional.empty();
        }
        try {
            JsonNode event = json.readTree(last);
            JsonNode limits = event.path("payload").path("rate_limits");
            if (limits.isMissingNode() || limits.isNull()) {
                return Optional.empty();
            }
            Instant observedAt = Instant.parse(event.path("timestamp").asString());
            List<Window> windows = Stream.of("primary", "secondary")
                    .map(limits::path)
                    .filter(node -> node.isObject() && node.has("resets_at"))
                    .map(node -> new Window(node.path("used_percent").asDouble(),
                            node.path("window_minutes").asInt(),
                            Instant.ofEpochSecond(node.path("resets_at").asLong())))
                    .toList();
            String plan = limits.path("plan_type").isString() ? limits.path("plan_type").asString() : null;
            return Optional.of(new Snapshot(observedAt, windows, plan));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
