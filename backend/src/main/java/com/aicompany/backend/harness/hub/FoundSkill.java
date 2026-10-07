package com.aicompany.backend.harness.hub;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A skill or knowledge item found in an application's folder (ADR-036 §2).
 * {@code id} is stable across scans -- the location and the path inside it --
 * so the console can point at it and a synchronisation can compare it.
 *
 * @param file        the {@code SKILL.md} (or the knowledge file)
 * @param folder      the skill's folder; the file's own folder for single files
 * @param fingerprint the quick fingerprint ({@link Fingerprints#quick})
 * @param marker      present when the Hub wrote this copy
 */
public record FoundSkill(String id, String app, String location, SkillLocations.Scope scope, String kind,
                         String name, String description, SkillCategories.Category category,
                         String plugin, String pluginVersion, String marketplace, Boolean enabled, String version,
                         Path file, Path folder, String relativePath, boolean writable, String fingerprint,
                         Instant modified, HubMarker marker) {

    public static String idOf(String location, String relativePath) {
        return Fingerprints.sha256(location + "|" + relativePath).substring(0, 24);
    }
}
