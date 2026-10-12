package com.aicompany.backend.software.dto;

import java.util.List;

/**
 * What was started, in full: the operator can see the exact command line the
 * control plane ran on their behalf. {@code fileOpened} (PHASE 33, ADR-040): the
 * command carries one file of the project.
 */
public record LaunchResponse(String key, List<String> command, String workingDirectory, boolean folderOpened,
                             boolean fileOpened) {
}
