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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Token usage Claude Code recorded on this machine, read from
 * {@code <claude-home>/projects/<project>/*.jsonl}.
 *
 * <p>Each assistant message carries {@code message.usage}. Claude Code writes
 * one line per content block of the same message, so entries are de-duplicated
 * by message id and request id. Only numbers, the model name and the timestamp
 * are read -- never content.
 *
 * <p>Claude Code does not record its limits, so this class measures tokens, not
 * percentages. The five-hour window is reconstructed the way Anthropic's
 * sessions behave: it starts at the hour of the first message after the
 * previous window ended, and lasts five hours.
 */
public class ClaudeCodeUsage {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeUsage.class);

    public static final Duration SESSION = Duration.ofHours(5);

    public record Entry(Instant at, String model, long input, long output, long cacheCreation, long cacheRead) {
        public long total() {
            return input + output + cacheCreation + cacheRead;
        }
    }

    public record Block(Instant start, Instant end, List<Entry> entries) {
    }

    private final Path claudeHome;
    private final JsonMapper json = JsonMapper.builder().build();

    public ClaudeCodeUsage(Path claudeHome) {
        this.claudeHome = claudeHome;
    }

    /** Every entry at or after {@code since}, oldest first. Files older than that are not opened. */
    public List<Entry> entriesSince(Instant since) {
        Path projects = claudeHome.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(projects, 3)) {
            files = walk.filter(path -> path.toString().endsWith(".jsonl"))
                    .filter(path -> modifiedAfter(path, since))
                    .toList();
        } catch (IOException e) {
            log.debug("Claude Code logs unreadable: {}", e.toString());
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            readInto(file, since, seen, entries);
        }
        entries.sort(Comparator.comparing(Entry::at));
        return entries;
    }

    /** The five-hour window that contains {@code now}, if one is open. */
    public static Optional<Block> activeBlock(List<Entry> entries, Instant now) {
        Block current = null;
        for (Entry entry : entries) {
            if (current == null || !entry.at().isBefore(current.end())) {
                Instant start = entry.at().truncatedTo(ChronoUnit.HOURS);
                current = new Block(start, start.plus(SESSION), new ArrayList<>());
            }
            current.entries().add(entry);
        }
        if (current == null || !now.isBefore(current.end()) || now.isBefore(current.start())) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    private void readInto(Path file, Instant since, Set<String> seen, List<Entry> out) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"usage\"") || !line.contains("\"assistant\"")) {
                    continue;
                }
                try {
                    JsonNode node = json.readTree(line);
                    JsonNode message = node.path("message");
                    JsonNode usage = message.path("usage");
                    if (!usage.isObject() || !node.path("timestamp").isString()) {
                        continue;
                    }
                    Instant at = Instant.parse(node.path("timestamp").asString());
                    if (at.isBefore(since)) {
                        continue;
                    }
                    String identity = message.path("id").asString("") + "|" + node.path("requestId").asString("");
                    if (!identity.equals("|") && !seen.add(identity)) {
                        continue;
                    }
                    out.add(new Entry(at, message.path("model").asString("unknown"),
                            usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong(),
                            usage.path("cache_creation_input_tokens").asLong(),
                            usage.path("cache_read_input_tokens").asLong()));
                } catch (RuntimeException unreadable) {
                    // A torn line (the file is being written) is skipped, not fatal.
                }
            }
        } catch (IOException e) {
            log.debug("Claude Code log {} unreadable: {}", file, e.toString());
        }
    }

    private static boolean modifiedAfter(Path path, Instant since) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isAfter(since);
        } catch (IOException e) {
            return false;
        }
    }
}
