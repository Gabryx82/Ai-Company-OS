package com.aicompany.backend.workspace.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * PHASE 33 (ADR-040): the two things the file explorer asks of the operator's desktop --
 * the Recycle Bin, and an Explorer window on a file. Tests replace it with a fake: no
 * test moves a file to the real Recycle Bin or opens a window.
 */
public interface DesktopShell {

    /** Why {@code path} cannot go to the Recycle Bin from here, or empty when it can. */
    Optional<String> recycleBinRefusal(Path path);

    /**
     * Moves a file or a folder, with everything in it, to the Recycle Bin: the operator
     * restores it from there. Never deletes for good.
     *
     * @throws IOException when Windows did not move it -- most often because another
     *                     program holds a file open
     */
    void moveToRecycleBin(Path path) throws IOException;

    /** Opens an Explorer window on the folder that holds {@code path}, with it selected. */
    void reveal(Path path);
}
