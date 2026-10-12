package com.aicompany.backend.support;

import com.aicompany.backend.workspace.desktop.DesktopShell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PHASE 33 (ADR-040): a desktop described by the test. The "Recycle Bin" is a temporary
 * folder the items are moved into -- so a test sees them leave the project and still
 * exist -- and Explorer windows are recorded, never opened.
 */
public class FakeDesktop implements DesktopShell {

    private final List<Path> recycled = new ArrayList<>();
    private final List<Path> revealed = new ArrayList<>();
    private volatile String refusal;
    private volatile boolean holdOpen;
    private Path bin;

    public synchronized void reset() {
        recycled.clear();
        revealed.clear();
        refusal = null;
        holdOpen = false;
    }

    /** Every item is refused, with this reason -- a machine without a Recycle Bin. */
    public void refuse(String reason) {
        this.refusal = reason;
    }

    /** Windows refuses the next moves, as when another program holds the file open. */
    public void holdOpen() {
        this.holdOpen = true;
    }

    public synchronized List<Path> recycled() {
        return List.copyOf(recycled);
    }

    public synchronized List<Path> revealed() {
        return List.copyOf(revealed);
    }

    /** Where an item moved to the fake bin ended up. */
    public synchronized Path inBin(int index) {
        return bin.resolve(String.valueOf(index));
    }

    @Override
    public Optional<String> recycleBinRefusal(Path path) {
        return Optional.ofNullable(refusal);
    }

    @Override
    public synchronized void moveToRecycleBin(Path path) throws IOException {
        if (holdOpen) {
            throw new IOException("Windows non l'ha spostato nel Cestino: forse è aperto in un altro programma.");
        }
        if (bin == null) {
            bin = Files.createTempDirectory("aicos-fake-bin-" + UUID.randomUUID());
        }
        Files.move(path, bin.resolve(String.valueOf(recycled.size())));
        recycled.add(path);
    }

    @Override
    public synchronized void reveal(Path path) {
        revealed.add(path);
    }
}
