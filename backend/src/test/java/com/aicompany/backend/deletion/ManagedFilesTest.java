package com.aicompany.backend.deletion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 30 (ADR-035 §5): the one place that removes managed files. A file goes
 * when the transaction commits and comes back when it rolls back; nothing outside
 * the managed folder, no link and no folder is ever touched; a file another
 * program holds open refuses the delete instead of half-doing it.
 */
class ManagedFilesTest {

    @TempDir
    Path root;

    private Path file(String relative) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, relative);
        return path;
    }

    /** Runs {@code body} as if inside a transaction, then completes it with {@code status}. */
    private static void inTransaction(int status, Runnable body) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            body.run();
            List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
            registered.forEach(s -> s.afterCompletion(status));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheFileGoesAtOnce() throws IOException {
        Path a = file("images/a.png");
        assertThat(ManagedFiles.delete(root, a)).isTrue();
        assertThat(a).doesNotExist();
        assertThat(ManagedFiles.delete(root, a)).as("already gone").isFalse();
    }

    @Test
    void aCommittedTransactionRemovesTheFilesAndARolledBackOnePutsThemBack() throws IOException {
        Path a = file("images/a.png");
        Path b = file("mockups/b.png");

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> {
            assertThat(ManagedFiles.delete(root, List.of(a, b))).containsExactly(a, b);
            // Staged: no longer under its name, not yet gone.
            assertThat(a).doesNotExist();
        });
        assertThat(a).hasContent("images/a.png");
        assertThat(b).hasContent("mockups/b.png");

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> ManagedFiles.delete(root, List.of(a, b)));
        assertThat(a).doesNotExist();
        assertThat(b).doesNotExist();
        try (var left = Files.walk(root)) {
            assertThat(left.filter(Files::isRegularFile)).isEmpty();
        }
    }

    /**
     * PHASE 32 (TD-70): what a crash between the commit and the removal leaves -- a staged
     * file -- is swept; a file a transaction still open has staged is not, because a
     * rollback still needs it; names that only look alike, and ordinary hidden files, stay.
     */
    @Test
    void theSweepRemovesWhatACrashLeftAndNeverWhatATransactionStillNeeds() throws IOException {
        Path leftByACrash = file("references/.photo.png.aicos-deleting-" + java.util.UUID.randomUUID());
        Path ordinaryHidden = file("references/.keep");
        Path lookAlike = file("references/.notes.md.aicos-deleting-not-a-uuid");
        Path kept = file("references/notes.md");
        Path inUse = file("references/sketch.png");

        TransactionSynchronizationManager.initSynchronization();
        try {
            ManagedFiles.delete(root, inUse);
            Path staged;
            try (var listing = Files.list(root.resolve("references"))) {
                staged = listing.filter(p -> p.getFileName().toString().startsWith(".sketch.png.aicos-deleting-"))
                        .findFirst().orElseThrow();
            }

            assertThat(ManagedFiles.sweep(root)).containsExactly(leftByACrash);
            assertThat(leftByACrash).doesNotExist();
            assertThat(staged).as("a rollback could still need it").exists();
            assertThat(ordinaryHidden).exists();
            assertThat(lookAlike).exists();
            assertThat(kept).exists();

            // The transaction rolls back: the file is back where it was, nothing was lost.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(inUse).exists();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(ManagedFiles.sweep(root.resolve("no-such-folder"))).isEmpty();
    }

    @Test
    void nothingOutsideTheRootNoFolderAndNoLinkIsTouched() throws IOException {
        Path outside = Files.createTempFile("managed-outside", ".png");
        try {
            assertThatThrownBy(() -> ManagedFiles.delete(root, outside)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ManagedFiles.delete(root, root.resolve("../" + outside.getFileName())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ManagedFiles.delete(root, root)).isInstanceOf(IllegalArgumentException.class);
            Files.createDirectories(root.resolve("images"));
            assertThatThrownBy(() -> ManagedFiles.delete(root, root.resolve("images")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(outside).exists();
            assertThat(root.resolve("images")).isDirectory();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * Windows: a file open in another program (here, a channel that does not share
     * delete access, the way an image viewer or an editor opens it) cannot be moved.
     * The delete is refused as a conflict, and the other files of the batch are put
     * back -- never half a selection.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileHeldOpenByAnotherProgramRefusesTheWholeBatch() throws IOException {
        Path first = file("images/first.png");
        Path held = file("images/held.png");
        try (FileChannel open = FileChannel.open(held, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThatThrownBy(() -> ManagedFiles.delete(root, List.of(first, held)))
                    .isInstanceOf(DeletionBlockedException.class)
                    .hasMessageContaining("held.png");
            assertThat(first).hasContent("images/first.png");
            assertThat(held).exists();
        }
    }

    @Test
    void stagedAndHiddenFilesAreNotListed() {
        assertThat(ManagedFiles.hidden(Path.of(".a.png" + ManagedFiles.STAGED_MARK + "x"))).isTrue();
        assertThat(ManagedFiles.hidden(Path.of(".gitkeep"))).isTrue();
        assertThat(ManagedFiles.hidden(Path.of("a.png"))).isFalse();
    }
}
