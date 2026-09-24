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
}
