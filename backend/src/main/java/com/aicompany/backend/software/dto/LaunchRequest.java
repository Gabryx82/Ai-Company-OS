package com.aicompany.backend.software.dto;

import jakarta.validation.constraints.Size;

/**
 * What a launch may ask for: a project, whose workspace folder -- read from the
 * database, never from this body -- becomes the program's folder (ADR-019 I2).
 * Everything else about the command line comes from the catalog.
 *
 * <p>PHASE 33 (ADR-040): {@code path}, with the project, names one file of its
 * folder to open -- relative, resolved and checked inside the folder by the server,
 * and passed only where the catalog's {@code fileArgs} put {@code {file}}.
 */
public record LaunchRequest(Long projectId, @Size(max = 1000) String path) {
}
