package com.aicompany.backend.software.dto;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.model.SoftwareCategory;
import com.aicompany.backend.software.service.SoftwareDeletionService;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        @Nullable String purpose,
        List<String> capabilities,
        List<String> projectTypes,
        LaunchKind launchKind,
        @Nullable String appId,
        @Nullable String executable,
        List<String> executableArgs,
        boolean openFolder,
        @Nullable String cliCommand,
        @Nullable String url,
        @Nullable String healthUrl,
        boolean embeddable,
        @Nullable String embedNote,
        boolean executionTarget,
        @Nullable String requirements,
        @Nullable String configuration,
        @Nullable String iconUrl,
        @Nullable String incompatibleReason,
        boolean enabled,
        Availability availability,
        @Nullable String availabilityDetail,
        @Nullable String resolvedExecutable,
        boolean launchable,
        long version,
        Instant createdAt,
        Instant updatedAt,
        // PHASE 32 (TD-68): an entry of the shipped catalog -- disabled, never deleted.
        boolean catalog) {

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
                launchable, s.getVersion(), s.getCreatedAt(), s.getUpdatedAt(),
                SoftwareDeletionService.shipped(s.getKey()));
    }
}
