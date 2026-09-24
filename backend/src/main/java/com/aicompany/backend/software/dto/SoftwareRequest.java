package com.aicompany.backend.software.dto;

import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.SoftwareCategory;
import com.aicompany.backend.software.model.SoftwareDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create and update share one body. {@code key} is required on create and must
 * be absent -- or equal to the path -- on update: a key is an identity, and an
 * identity is not edited.
 */
public record SoftwareRequest(
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$",
                message = "must be 2-64 lower-case letters, digits or dashes")
        String key,
        @NotBlank @Size(max = 120) String name,
        @NotNull SoftwareCategory category,
        @NotBlank @Size(max = 200) String role,
        @Size(max = 1000) String purpose,
        @Size(max = 60) List<@Size(max = 60) String> capabilities,
        @Size(max = 20) List<@Size(max = 30) String> projectTypes,
        @NotNull LaunchKind launchKind,
        @Size(max = 300) String appId,
        @Size(max = 500) String executable,
        @Size(max = 10) List<@Size(max = 300) String> executableArgs,
        Boolean openFolder,
        @Size(max = 200) String cliCommand,
        @Size(max = 500) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String url,
        @Size(max = 500) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String healthUrl,
        Boolean embeddable,
        @Size(max = 500) String embedNote,
        Boolean executionTarget,
        @Size(max = 1000) String requirements,
        @Size(max = 1000) String configuration,
        @Size(max = 500) String iconUrl,
        @Size(max = 500) String incompatibleReason,
        Boolean enabled) {

    public SoftwareDefinition definition() {
        return new SoftwareDefinition(name, category, role, purpose, capabilities, projectTypes, launchKind,
                appId, executable, executableArgs, Boolean.TRUE.equals(openFolder), cliCommand, url, healthUrl,
                Boolean.TRUE.equals(embeddable), embedNote, Boolean.TRUE.equals(executionTarget), requirements,
                configuration, iconUrl, incompatibleReason, enabled);
    }
}
