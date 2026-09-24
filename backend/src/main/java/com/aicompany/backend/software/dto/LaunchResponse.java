package com.aicompany.backend.software.dto;

import java.util.List;

/**
 * What was started, in full: the operator can see the exact command line the
 * control plane ran on their behalf.
 */
public record LaunchResponse(String key, List<String> command, String workingDirectory, boolean folderOpened) {
}
