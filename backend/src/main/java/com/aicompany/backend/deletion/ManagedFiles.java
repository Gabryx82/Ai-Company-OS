package com.aicompany.backend.deletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deletes files AI Company OS manages, and nothing else (PHASE 30, ADR-035 §5).
 *
 * <p><strong>Never a path from a request.</strong> The caller computes the managed
 * root itself -- a project's {@code references/}, the skill library -- from what it
 * owns, and has already resolved each file under it with its own guard
 * ({@code WorkspacePaths}, the library's key layout). This class checks the same
 * thing again, because it is the last line before the disk: the normalised path
 * and its real path must be inside the root, and the target must be a regular
 * file, not a link and not a folder. A violation here is a caller's bug, and it
 * fails as one.
 *
 * <p><strong>Staged, so that a row and its file leave together or not at all.</strong>
 * Each file is first renamed, in its own folder, to a hidden name no listing
 * shows ({@code .<name>.aicos-deleting-<uuid>}): a rename in one folder is atomic,
 * and it fails while another program holds the file open -- which on Windows is
 * the common reason a delete cannot happen, and is reported as such. When the
 * surrounding transaction commits, the staged files are removed; when it rolls
 * back, they are renamed back where they were. Without a transaction they are
 * removed at once. A batch is staged whole or not at all.
 *
 * <p>What remains possible is a crash between the commit and the removal: the
 * row is gone and a hidden staged file stays on disk, invisible to every listing.
 * {@link #sweep} removes those, and the control plane runs it at every start
 * (PHASE 32, TD-70); a file staged by a transaction still open is never swept.
 * The reverse -- a file gone while its row stays -- needs the database to fail
 * the commit after the rename, and then the rollback puts the file back.
 */
public final class ManagedFiles {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedFiles.class);

    /** The marker of a staged file. Listings that show a managed folder skip names that contain it. */
    public static final String STAGED_MARK = ".aicos-deleting-";

    private record Staged(Path original, Path hidden) {
    }

    /** A staged name: {@code .<name>.aicos-deleting-<uuid>}. */
    private static final Pattern STAGED_NAME = Pattern.compile(
            "^\\..+\\.aicos-deleting-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Staged by a transaction that has not finished: it may still need them back. */
    private static final Set<Path> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private ManagedFiles() {
    }

    /** Whether a file name is one of ours, half-deleted, or a hidden file: listings leave those out. */
    public static boolean hidden(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".") || name.contains(STAGED_MARK);
    }

    /**
     * Deletes {@code files}, all inside {@code root}, tied to the current
     * transaction. Files that do not exist are skipped -- the end state the caller
     * asked for is already true. Returns the files that existed and are deleted.
     *
     * @throws DeletionBlockedException when a file cannot be moved (in use by another program)
     * @throws IllegalArgumentException when a file is outside the root, a link or a folder
     */
    public static List<Path> delete(Path root, Collection<Path> files) {
        Path base = root.toAbsolutePath().normalize();
        List<Path> present = new ArrayList<>();
        for (Path file : files) {
            Path target = file.toAbsolutePath().normalize();
            requireInside(base, target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(target + " is not a regular file");
            }
            if (!present.contains(target)) {
                present.add(target);
            }
        }
        List<Staged> staged = new ArrayList<>();
        List<Path> deleted = new ArrayList<>();
        for (Path target : present) {
            Path hidden = target.resolveSibling("." + target.getFileName() + STAGED_MARK + UUID.randomUUID());
            IN_FLIGHT.add(hidden);
            try {
                Files.move(target, hidden, StandardCopyOption.ATOMIC_MOVE);
                staged.add(new Staged(target, hidden));
                deleted.add(target);
            } catch (NoSuchFileException gone) {
                // Removed by somebody else between the check and the move: already the end state.
                IN_FLIGHT.remove(hidden);
            } catch (IOException e) {
                IN_FLIGHT.remove(hidden);
                restore(staged);
                throw new DeletionBlockedException("'" + target.getFileName() + "' cannot be removed: it may be open "
                        + "in another program. Close it and try again.");
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        purge(staged);
                    } else {
                        restore(staged);
                    }
                }
            });
        } else {
            purge(staged);
        }
        return deleted;
    }

    /** One file; see {@link #delete(Path, Collection)}. True when it existed. */
    public static boolean delete(Path root, Path file) {
        return !delete(root, List.of(file)).isEmpty();
    }

    private static void requireInside(Path base, Path target) {
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IllegalArgumentException(target + " is not inside the managed folder " + base);
        }
        try {
            Path existing = target.getParent();
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && Files.exists(base)
                    && !existing.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException(target + " resolves outside the managed folder " + base);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(target + " cannot be resolved", e);
        }
    }

    private static void purge(List<Staged> staged) {
        for (Staged s : staged) {
            try {
                Files.deleteIfExists(s.hidden());
            } catch (IOException e) {
                LOG.warn("Deleted {} but could not remove its staged copy {}: {}", s.original(), s.hidden(), e.getMessage());
            } finally {
                IN_FLIGHT.remove(s.hidden());
            }
        }
    }

    /**
     * Removes the staged files left under {@code root} by a crash between a commit
     * and the removal, or by a removal that failed because a program held the file.
     * Only names this class gives ({@code .<name>.aicos-deleting-<uuid>}), only
     * regular files, never one a transaction still open has staged. Returns what it
     * removed; a folder that does not exist has nothing to sweep.
     */
    public static List<Path> sweep(Path root) {
        List<Path> removed = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return removed;
        }
        try (Stream<Path> files = Files.walk(root, 16)) {
            for (Path file : files.filter(f -> STAGED_NAME.matcher(f.getFileName().toString()).matches()).toList()) {
                if (IN_FLIGHT.contains(file) || IN_FLIGHT.contains(file.toAbsolutePath().normalize())
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                    removed.add(file);
                } catch (IOException e) {
                    LOG.warn("Could not sweep the staged file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not sweep {}: {}", root, e.getMessage());
        }
        return removed;
    }

    private static void restore(List<Staged> staged) {
        for (Staged s : staged) {
            try {
                Files.move(s.hidden(), s.original(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.error("Could not put back {} (staged as {}) after a failed delete: {}", s.original(), s.hidden(),
                        e.getMessage());
            } finally {
                IN_FLIGHT.remove(s.hidden());
            }
        }
    }
}
