package com.aicompany.backend.harness.hub;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * An item found in an application's folders (ADR-036 §2): a skill, a plugin, a
 * connector (MCP server) or a knowledge item. {@code id} is stable across scans --
 * the location and the path inside it -- so the console can point at it and a
 * synchronisation can compare it.
 *
 * @param kind        {@code SKILL}, {@code PLUGIN}, {@code CONNECTOR} or {@code KNOWLEDGE}
 * @param file        the {@code SKILL.md}, the plugin's manifest, the knowledge file; for a
 *                    connector the configuration it was read from -- never shown as it is
 * @param folder      the item's folder; the file's own folder for single files
 * @param fingerprint the quick fingerprint ({@link Fingerprints#quick})
 * @param marker      present when the Hub wrote this copy
 * @param details     what the console shows about it, label by label: a plugin's components,
 *                    a connector's transport and who provides it. Never a secret
 */
public record FoundSkill(String id, String app, String location, SkillLocations.Scope scope, String kind,
                         String name, String description, SkillCategories.Category category,
                         String plugin, String pluginVersion, String marketplace, Boolean enabled, String version,
                         Path file, Path folder, String relativePath, boolean writable, String fingerprint,
                         Instant modified, HubMarker marker, Map<String, String> details) {

    public static final String SKILL = "SKILL";
    public static final String PLUGIN = "PLUGIN";
    public static final String CONNECTOR = "CONNECTOR";
    public static final String KNOWLEDGE = "KNOWLEDGE";

    public static String idOf(String location, String relativePath) {
        return Fingerprints.sha256(location + "|" + relativePath).substring(0, 24);
    }
}
