package com.aicompany.backend.software.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The workspace folder of a registered project, read from the database -- the
 * only variable argument a launch may carry (ADR-019 I2). Implemented by the
 * project workspace (PHASE 9, ADR-020).
 */
public interface ProjectFolders {

    Optional<Path> folderOf(Long projectId);

    /**
     * PHASE 33 (ADR-040): one regular file of the project's folder, from a path relative
     * to it -- refused when it leaves the folder (ADR-020 W2, links included), missing
     * when it is not there. The second argument «Apri con» may carry.
     */
    Path fileOf(Long projectId, String relative);
}
