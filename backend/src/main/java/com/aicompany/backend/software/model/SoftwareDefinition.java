package com.aicompany.backend.software.model;

import java.util.List;

/**
 * Everything a catalog entry says, minus its key and bookkeeping. The shape the
 * catalog file, the create request and the update request share, so that there
 * is one list of fields and not three that drift.
 */
public record SoftwareDefinition(
        String name,
        SoftwareCategory category,
        String role,
        String purpose,
        List<String> capabilities,
        List<String> projectTypes,
        LaunchKind launchKind,
        String appId,
        String executable,
        List<String> executableArgs,
        boolean openFolder,
        String cliCommand,
        String url,
        String healthUrl,
        boolean embeddable,
        String embedNote,
        boolean executionTarget,
        String requirements,
        String configuration,
        String iconUrl,
        String incompatibleReason,
        Boolean enabled) {
}
