package com.aicompany.backend.journal;

import com.aicompany.backend.graph.code.CodeGraphScanner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * PHASE 37 (ADR-047): what an agent changed in the working folder since the task was handed
 * to it. At each handoff the text files of the folder are copied, as they are, under
 * {@code <aicos.home>/snapshots/task-<id>/<copy>/}, tied to that handoff; the changes are the folder now against
 * that copy -- files added, modified, deleted, and line by line what changed in each. No git
 * needed: most project folders are not repositories. Only the latest copy of a task is kept.
 * Read only: nothing in the working folder is ever written here.
 */
@Service
public class WorkChanges {

    private static final Logger log = LoggerFactory.getLogger(WorkChanges.class);

    /** Beyond these a folder is copied in part, and the changes say so. */
    static final int MAX_FILES = CodeGraphScanner.MAX_FILES;
    static final long MAX_BYTES = CodeGraphScanner.MAX_BYTES;
    static final long MAX_TOTAL = 64L * 1024 * 1024;
    /** A file longer than this is listed, not compared line by line. */
    static final int MAX_LINES = 20_000;
    private static final String MANIFEST = "manifest.tsv";
    private static final String TRUNCATED = "truncated";

    public enum Status { ADDED, MODIFIED, DELETED }

    /** One changed file; {@code comparable} false for a file too long or not text, whose lines are not counted. */
    public record ChangedFile(String path, Status status, int additions, int deletions, boolean comparable,
                             @Nullable Instant modifiedAt) {
    }

    /**
     * The changes of a task. {@code since} is when the copy was taken (the handoff); null with
     * {@code tracked} false when there is no copy to compare with -- a task never handed off, or
     * handed off before copies were taken.
     */
    public record TaskChanges(boolean tracked, @Nullable Instant since, boolean truncated, int additions, int deletions,
                          List<ChangedFile> files) {
        static TaskChanges untracked() {
            return new TaskChanges(false, null, false, 0, 0, List.of());
        }
    }

    public record FileDiff(String path, Status status, int additions, int deletions, boolean comparable,
                           @Nullable String note, List<LineDiff.DiffHunk> hunks) {
    }

    private record Seen(long size, long modified) {
    }

    private record Snapshot(Path root, Instant at, boolean truncated, Map<String, Seen> files) {
    }

    private final Path root;

    public WorkChanges(@Value("${aicos.snapshots.root:${aicos.home:${user.home}/.aicos}/snapshots}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * The handoff a copy belongs to, as the database has it: its id and when it was made. A copy is
     * read only for that handoff -- task ids come again in a database made anew, handoffs with the
     * same id and the same instant do not.
     */
    public record Baseline(long handoffId, Instant at) {
        String marker() {
            return "handoff-" + handoffId + "-" + at.truncatedTo(ChronoUnit.MILLIS).toEpochMilli();
        }
    }

    /**
     * Copies the text files of the working folder as they are now, for the changes to come. Called by
     * the handoff before the tool is opened; {@link #bind} then ties the copy to the handoff once it is
     * saved. Written in place, the manifest last: a copy without it is not complete and is never read.
     * A copy that cannot be made never fails the handoff -- the changes then say they are not tracked.
     *
     * @return the copy's name, for {@link #bind}; null when it could not be made
     */
    public @Nullable String snapshot(long taskId, Path folder) {
        String name = System.currentTimeMillis() + "-" + Long.toHexString(System.nanoTime());
        Path copy = root.resolve("task-" + taskId).resolve(name);
        try {
            Files.createDirectories(copy.resolve("files"));
            Map<String, Seen> files = new LinkedHashMap<>();
            boolean truncated = walk(folder, (relative, file, attributes) -> {
                Path target = copy.resolve("files").resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                files.put(relative, new Seen(attributes.size(), attributes.lastModifiedTime().toMillis()));
            });
            StringBuilder manifest = new StringBuilder(truncated ? TRUNCATED + "\n" : "");
            files.forEach((path, seen) -> manifest.append(seen.size()).append('\t').append(seen.modified()).append('\t')
                    .append(path).append('\n'));
            Files.writeString(copy.resolve(MANIFEST), manifest, StandardCharsets.UTF_8);
            return name;
        } catch (IOException | UncheckedIOException | SecurityException failed) {
            log.warn("The working folder of task {} could not be copied for its changes: {} {}", taskId,
                    failed.getClass().getSimpleName(), failed.getMessage());
            deleteQuietly(copy);
            return null;
        }
    }

    /** Ties a copy to the handoff it was taken for, and lets the task's older copies go. */
    public void bind(long taskId, @Nullable String name, Baseline baseline) {
        if (name == null) {
            return;
        }
        Path taskRoot = root.resolve("task-" + taskId);
        Path copy = taskRoot.resolve(name);
        try {
            Files.writeString(copy.resolve(baseline.marker()), "", StandardCharsets.UTF_8);
            try (Stream<Path> older = Files.list(taskRoot)) {
                older.filter(p -> !p.equals(copy)).forEach(WorkChanges::deleteQuietly);
            }
        } catch (IOException | UncheckedIOException | SecurityException failed) {
            log.warn("The copy of task {} could not be tied to handoff {}: {}", taskId, baseline.handoffId(), failed.getMessage());
        }
    }

    /** The folder now against the copy taken at the task's latest handoff, the most recently touched first. */
    public TaskChanges changes(long taskId, @Nullable Baseline baseline, @Nullable Path folder) {
        Optional<Snapshot> snapshot = latest(taskId, baseline);
        if (snapshot.isEmpty() || folder == null || !Files.isDirectory(folder)) {
            return TaskChanges.untracked();
        }
        Map<String, Seen> now = new LinkedHashMap<>();
        boolean truncated;
        try {
            truncated = walk(folder, (relative, file, attributes) ->
                    now.put(relative, new Seen(attributes.size(), attributes.lastModifiedTime().toMillis())));
        } catch (IOException | UncheckedIOException failed) {
            return TaskChanges.untracked();
        }
        Snapshot before = snapshot.get();
        List<ChangedFile> files = new ArrayList<>();
        for (Map.Entry<String, Seen> entry : now.entrySet()) {
            Seen old = before.files().get(entry.getKey());
            if (old != null && old.equals(entry.getValue())) {
                continue;
            }
            FileDiff diff = compare(before, folder, entry.getKey(), old != null, true);
            if (diff != null) {
                files.add(new ChangedFile(diff.path(), diff.status(), diff.additions(), diff.deletions(), diff.comparable(),
                        Instant.ofEpochMilli(entry.getValue().modified())));
            }
        }
        for (String path : before.files().keySet()) {
            if (!now.containsKey(path)) {
                FileDiff diff = compare(before, folder, path, true, false);
                if (diff != null) {
                    files.add(new ChangedFile(path, Status.DELETED, diff.additions(), diff.deletions(), diff.comparable(), null));
                }
            }
        }
        files.sort(Comparator.comparing((ChangedFile f) -> f.modifiedAt() == null ? Instant.MIN : f.modifiedAt()).reversed()
                .thenComparing(ChangedFile::path));
        return new TaskChanges(true, before.at(), truncated || before.truncated(),
                files.stream().mapToInt(ChangedFile::additions).sum(), files.stream().mapToInt(ChangedFile::deletions).sum(),
                List.copyOf(files));
    }

    /** One file of the changes, line by line; empty when it did not change since the handoff. */
    public Optional<FileDiff> diff(long taskId, @Nullable Baseline baseline, @Nullable Path folder, String path) {
        Optional<Snapshot> snapshot = latest(taskId, baseline);
        if (snapshot.isEmpty() || folder == null || !Files.isDirectory(folder)) {
            return Optional.empty();
        }
        String relative = path.replace('\\', '/');
        Path current = inside(folder, relative);
        boolean was = snapshot.get().files().containsKey(relative);
        boolean is = current != null && Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) && !excludedFile(relative);
        if (!was && !is) {
            return Optional.empty();
        }
        return Optional.ofNullable(compare(snapshot.get(), folder, relative, was, is));
    }

    // --- comparing ------------------------------------------------------------------------------

    private @Nullable FileDiff compare(Snapshot before, Path folder, String relative, boolean was, boolean is) {
        Status status = !was ? Status.ADDED : !is ? Status.DELETED : Status.MODIFIED;
        Text old = was ? read(inside(before.root().resolve("files"), relative)) : Text.EMPTY;
        Text now = is ? read(inside(folder, relative)) : Text.EMPTY;
        if (status == Status.MODIFIED && old.comparable() && now.comparable() && old.lines().equals(now.lines())) {
            return null;
        }
        if (!old.comparable() || !now.comparable()) {
            String note = old.tooLong() || now.tooLong() ? "File troppo lungo da confrontare riga per riga."
                    : "Non è un file di testo: non si confronta riga per riga.";
            return new FileDiff(relative, status, 0, 0, false, note, List.of());
        }
        LineDiff.DiffResult result = LineDiff.diff(old.lines(), now.lines());
        return new FileDiff(relative, status, result.additions(), result.deletions(), true, null, result.hunks());
    }

    private record Text(List<String> lines, boolean comparable, boolean tooLong) {
        static final Text EMPTY = new Text(List.of(), true, false);
    }

    private static Text read(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Text.EMPTY;
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                return new Text(List.of(), false, true);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (binary(bytes, bytes.length)) {
                return new Text(List.of(), false, false);
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString();
            List<String> lines = text.isEmpty() ? List.of() : Arrays.asList(text.split("\\R", -1));
            if (!lines.isEmpty() && lines.getLast().isEmpty()) {
                lines = lines.subList(0, lines.size() - 1);
            }
            return lines.size() > MAX_LINES ? new Text(List.of(), false, true) : new Text(lines, true, false);
        } catch (IOException unreadable) {
            return new Text(List.of(), false, false);
        }
    }

    // --- the folder and the copies ---------------------------------------------------------------

    @FunctionalInterface
    private interface Visit {
        void file(String relative, Path file, BasicFileAttributes attributes) throws IOException;
    }

    /** The text files of a folder that count as work, as CodeGraphScanner skips; true when cut short. */
    private boolean walk(Path folder, Visit visit) throws IOException {
        Path base = folder.toAbsolutePath().normalize();
        int[] count = {0};
        long[] total = {0};
        boolean[] cut = {false};
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return !dir.equals(base) && CodeGraphScanner.SKIPPED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = base.relativize(file).toString().replace('\\', '/');
                if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES || excludedFile(relative) || !isText(file, attributes)) {
                    return FileVisitResult.CONTINUE;
                }
                if (count[0] >= MAX_FILES || total[0] + attributes.size() > MAX_TOTAL) {
                    cut[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                count[0]++;
                total[0] += attributes.size();
                visit.file(relative, file, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failed) {
                return FileVisitResult.CONTINUE;
            }
        });
        return cut[0];
    }

    private record Known(long size, long modified, boolean text) {
    }

    /** Whether each version of a file is text, so that a folder watched every few seconds is not read every time. */
    private final Map<String, Known> kinds = new ConcurrentHashMap<>();

    private boolean isText(Path file, BasicFileAttributes attributes) {
        String key = file.toString();
        long size = attributes.size();
        long modified = attributes.lastModifiedTime().toMillis();
        Known known = kinds.get(key);
        if (known != null && known.size() == size && known.modified() == modified) {
            return known.text();
        }
        boolean text = text(file);
        if (kinds.size() > 100_000) {
            kinds.clear();
        }
        kinds.put(key, new Known(size, modified, text));
        return text;
    }

    /** The agent's diary changes at every step: it has its own view, not a place among the code. */
    private static boolean excludedFile(String relative) {
        return relative.endsWith(".diario.md");
    }

    private static boolean text(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8000);
            return !binary(head, head.length);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static boolean binary(byte[] bytes, int length) {
        for (int i = 0; i < Math.min(length, 8000); i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** The complete copy tied to that handoff, if there is one. */
    private Optional<Snapshot> latest(long taskId, @Nullable Baseline baseline) {
        Path taskRoot = root.resolve("task-" + taskId);
        if (baseline == null || !Files.isDirectory(taskRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> copies = Files.list(taskRoot)) {
            Optional<Path> newest = copies.filter(p -> Files.isRegularFile(p.resolve(baseline.marker())))
                    .filter(p -> Files.isRegularFile(p.resolve(MANIFEST)))
                    .findFirst();
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Seen> files = new LinkedHashMap<>();
            boolean truncated = false;
            for (String line : Files.readAllLines(newest.get().resolve(MANIFEST), StandardCharsets.UTF_8)) {
                if (line.equals(TRUNCATED)) {
                    truncated = true;
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    files.put(parts[2], new Seen(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
                }
            }
            return Optional.of(new Snapshot(newest.get(), baseline.at(), truncated, files));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A relative path resolved inside a folder, or null when it would leave it. */
    private static @Nullable Path inside(Path folder, String relative) {
        Path base = folder.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) && !resolved.equals(base) ? resolved : null;
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> all = Files.walk(path)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a copy left behind is only disk space; the newest copy is the one read
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }
}
