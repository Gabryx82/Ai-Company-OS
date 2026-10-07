package com.aicompany.backend.harness.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * How the Hub writes a skill folder (ADR-036 §7), in the library or in an
 * application: the new files are prepared in a staging folder of AI Company OS,
 * the folder being replaced -- if any -- is moved whole into
 * {@code <aicos.home>/backups/skills/}, and only then the staged folder is moved
 * into place. Nothing is ever deleted: a replaced or removed folder is a backup
 * the operator can put back.
 *
 * <p>Inside a transaction the swap is undone when the transaction rolls back: the
 * new folder leaves, the backup returns. One write at a time: two writes of the
 * same folder interleaved could otherwise merge into it.
 */
@Component
public class HubFiles {

    private static final Logger LOG = LoggerFactory.getLogger(HubFiles.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** What a write changed, so that it can be undone. */
    public record Swap(Path folder, Optional<Path> backup) {
    }

    private final Path backups;
    private final Path staging;

    public HubFiles(@Value("${aicos.home:${user.home}/.aicos}") String home) {
        Path root = Path.of(home).toAbsolutePath().normalize();
        this.backups = root.resolve("backups").resolve("skills");
        this.staging = root.resolve("hub-staging");
    }

    public Path backups() {
        return backups;
    }

    /**
     * Makes {@code folder} hold exactly {@code files} (paths relative to it, checked
     * by {@link PackagePaths}), keeping what was there as a backup labelled {@code label}.
     */
    public synchronized Swap write(Path folder, Map<String, byte[]> files, String label) {
        Path stage = staging.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(stage);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path target = PackagePaths.resolve(stage, file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            Files.createDirectories(folder.getParent());
            Optional<Path> backup = Files.exists(folder, LinkOption.NOFOLLOW_LINKS) ? Optional.of(backup(folder, label)) : Optional.empty();
            try {
                move(stage, folder);
            } catch (IOException | UncheckedIOException e) {
                backup.ifPresent(b -> restore(b, folder));
                throw e;
            }
            Swap swap = new Swap(folder, backup);
            undoOnRollback(swap);
            return swap;
        } catch (IOException e) {
            throw HubProblemException.conflict(writeFailure(folder, e));
        } finally {
            deleteQuietly(stage);
        }
    }

    /** Moves a folder out of the way into the backups, whole; returns where it went. */
    public synchronized Path backup(Path folder, String label) {
        String safe = label.replaceAll("[^A-Za-z0-9._-]+", "-");
        Path target = backups.resolve(safe + "-" + ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP)
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(backups);
            move(folder, target);
            return target;
        } catch (IOException e) {
            throw HubProblemException.conflict(writeFailure(folder, e));
        }
    }

    private void undoOnRollback(Swap swap) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Path aside = backup(swap.folder(), "annullato-" + swap.folder().getFileName());
                        swap.backup().ifPresent(b -> restore(b, swap.folder()));
                        LOG.info("Rolled back the write of {} (kept as {})", swap.folder(), aside);
                    } catch (RuntimeException e) {
                        LOG.warn("Could not roll back the write of {}", swap.folder(), e);
                    }
                }
            }
        });
    }

    private void restore(Path backup, Path folder) {
        try {
            move(backup, folder);
        } catch (IOException e) {
            LOG.warn("Could not restore {} from {}", folder, backup, e);
        }
    }

    /** A rename when the two folders share a volume; a copy and a removal of the copy's source otherwise. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException e) {
            copyTree(from, to);
            deleteQuietly(from);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Only for the Hub's own staging folders. */
    private static void deleteQuietly(Path folder) {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Could not remove {}", p, e);
                }
            });
        } catch (IOException e) {
            LOG.debug("Could not remove {}", folder, e);
        }
    }

    private static String writeFailure(Path folder, IOException e) {
        if (e instanceof FileSystemException fs && fs.getReason() != null
                && fs.getReason().toLowerCase(java.util.Locale.ROOT).contains("process")) {
            return "La cartella " + folder + " è aperta in un altro programma: chiudilo e riprova";
        }
        return "La cartella " + folder + " non si può scrivere: " + e.getMessage();
    }
}
