package com.aicompany.backend.software.dto;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.model.SoftwareCategory;

import java.time.Instant;
import java.util.List;

/**
 * A catalog entry and what detection says about it right now. {@code availability}
 * is observed at read time and never stored (ADR-018 §5).
 */
public record SoftwareResponse(
        Long id,
        String key,
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
        boolean enabled,
        Availability availability,
        String availabilityDetail,
        String resolvedExecutable,
        boolean launchable,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static SoftwareResponse from(Software s, Detection d) {
        boolean launchable = s.isEnabled() && switch (s.getLaunchKind()) {
            case WEB -> false;
            case LOCAL_SERVICE -> d.availability() == Availability.STOPPED
                    && (d.executable() != null || s.getAppId() != null);
            case DESKTOP, CLI -> d.availability() == Availability.INSTALLED;
        };
        return new SoftwareResponse(s.getId(), s.getKey(), s.getName(), s.getCategory(), s.getRole(),
                s.getPurpose(), s.getCapabilities(), s.getProjectTypes(), s.getLaunchKind(), s.getAppId(),
                s.getExecutable(), s.getExecutableArgs(), s.isOpenFolder(), s.getCliCommand(), s.getUrl(),
                s.getHealthUrl(), s.isEmbeddable(), s.getEmbedNote(), s.isExecutionTarget(), s.getRequirements(),
                s.getConfiguration(), s.getIconUrl(), s.getIncompatibleReason(), s.isEnabled(),
                d.availability(), d.detail(), d.executable() == null ? null : d.executable().toString(),
                launchable, s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
