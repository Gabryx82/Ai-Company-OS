package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.controller.HarnessController.ResourceResponse;
import com.aicompany.backend.harness.model.HarnessResource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Knowledge Hub across applications and marketplaces (PHASE 31, ADR-036):
 * what is installed where, what the marketplaces offer, what the Hub installs,
 * copies, keeps in step and updates.
 */
@RestController
public class SkillHubController {

    public record SkillHubInstalled(Instant scannedAt, @Nullable Instant lastSync, long syncIntervalSeconds,
                            List<SkillHubService.AppView> apps, List<SkillHubService.SkillView> skills,
                            List<SkillCategories.Category> categories, List<String> notes, SkillHubService.SkillHubSummary summary) {
    }

    public record ImportRequest(@Size(max = 64) String key) {
    }

    /** PHASE 32 (TD-76): {@code category} one of the Hub's keys; empty gives the skill back to the heuristic. */
    public record SkillCategoryRequest(@NotBlank @Size(max = 200) String name, @Nullable @Size(max = 40) String category) {
    }

    public record InstallRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 500) String ref,
                                 @Size(max = 64) String key, @Size(max = 12) List<@Size(max = 40) String> targets) {
    }

    public record DeployRequest(boolean replace) {
    }

    /** PHASE 32 (TD-80): a marketplace and the reference of the package there. */
    public record SourceRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 500) String ref) {
    }

    public record ApplyRequest(@NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 120) String> ids, boolean overwrite) {
    }

    public record ApplyResponse(List<SkillUpdates.Applied> results) {
    }

    public record SkillFolderOpened(String folder) {
    }

    /** {@code values}: PHASE 32 (TD-88), the plugin's options; they go to the application and are never kept. */
    public record PluginInstallRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 200) String ref,
                                       @NotBlank @Size(max = 40) String target, @Size(max = 64) String confirmation,
                                       @Size(max = 40) Map<@Size(max = 64) String, @Size(max = 4000) String> values) {
    }

    /** {@code values}: what the connector needs (API keys...), passed to the applications and never kept. */
    public record ConnectorInstallRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 300) String ref,
                                          @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 40) String> targets,
                                          @Size(max = 64) String name, @Size(max = 40) Map<@Size(max = 64) String, @Size(max = 4000) String> values,
                                          Boolean replace) {
    }

    public record ConnectorInstallResponse(List<HubInstaller.InstallOutcome> results) {
    }

    private final SkillHubService hub;
    private final SkillMarketplaces markets;
    private final SkillUpdates updates;
    private final SkillSync sync;

    private final HubInstaller installer;

    public SkillHubController(SkillHubService hub, SkillMarketplaces markets, SkillUpdates updates, SkillSync sync,
                              HubInstaller installer) {
        this.hub = hub;
        this.markets = markets;
        this.updates = updates;
        this.sync = sync;
        this.installer = installer;
    }

    // --- installed ---------------------------------------------------------------------

    /** Every skill and knowledge item on this machine, by application, with the library's. {@code ?rescan=true} reads the disk now. */
    @GetMapping("/api/skill-hub/installed")
    public SkillHubInstalled installedSkills(@RequestParam(defaultValue = "false") boolean rescan) {
        SkillHubService.InstalledView view = hub.installed(rescan);
        Duration interval = sync.interval();
        return new SkillHubInstalled(view.scannedAt(), sync.lastRun(), interval.isNegative() ? 0 : interval.toSeconds(), view.apps(),
                view.skills(), view.categories(), view.notes(), view.summary());
    }

    @GetMapping("/api/skill-hub/installed/{id}")
    public SkillHubService.FoundDetail installedSkill(@PathVariable String id) {
        return hub.detail(id);
    }

    @PostMapping("/api/skill-hub/installed/{id}/open")
    public SkillFolderOpened openInstalledSkill(@PathVariable String id) {
        return new SkillFolderOpened(hub.open(id));
    }

    /** Copies a skill found in an application into the library, remembering its source. */
    @PostMapping("/api/skill-hub/installed/{id}/import")
    public ResponseEntity<ResourceResponse> importInstalledSkill(@PathVariable String id, @Valid @RequestBody(required = false) ImportRequest body) {
        HarnessResource created = hub.importFound(id, body == null ? null : body.key());
        return ResponseEntity.created(URI.create("/api/resources/" + created.getKey() + "/file"))
                .body(ResourceResponse.from(created));
    }

    /** PHASE 32 (TD-76): the operator's category for a skill, by name, over the keyword heuristic. */
    @PutMapping("/api/skill-hub/categories")
    public ResponseEntity<Void> setSkillCategory(@Valid @RequestBody SkillCategoryRequest body, Authentication caller) {
        hub.categorize(body.name(), body.category(), caller.getName());
        return ResponseEntity.noContent().build();
    }

    // --- synchronisation -----------------------------------------------------------------

    /** Re-reads the library folder and every application now, and says what changed since the last time. */
    @PostMapping("/api/skill-hub/sync")
    public SkillSync.Report syncSkills() {
        return sync.sync(true);
    }

    @GetMapping("/api/skill-hub/events")
    public List<SkillSync.Event> skillSyncEvents(@RequestParam(defaultValue = "100") int limit) {
        return sync.events(limit);
    }

    // --- marketplaces -----------------------------------------------------------------------

    @GetMapping("/api/skill-hub/marketplaces")
    public List<SkillMarketplaces.Provider> skillMarketplaces() {
        return markets.providers();
    }

    @GetMapping("/api/skill-hub/marketplaces/search")
    public SkillMarketplaces.SearchResults searchSkillMarketplaces(@RequestParam String q,
                                                  @RequestParam(required = false) List<String> provider,
                                                  @RequestParam(required = false) String kind) {
        return markets.search(q, provider, kind);
    }

    @GetMapping("/api/skill-hub/marketplaces/{provider}/browse")
    public List<SkillMarketplaces.Result> browseSkillMarketplace(@PathVariable String provider) {
        return markets.browse(provider);
    }

    /** What installing would bring: files, the SKILL.md, the marketplace's security notes. Nothing is written. */
    @GetMapping("/api/skill-hub/marketplaces/{provider}/package")
    public SkillMarketplaces.Preview previewSkillPackage(@PathVariable String provider, @RequestParam String ref) {
        return markets.preview(provider, ref);
    }

    @PostMapping("/api/skill-hub/install")
    public ResponseEntity<SkillHubService.InstallResult> installSkillPackage(@Valid @RequestBody InstallRequest body) {
        SkillHubService.InstallResult result = hub.install(body.provider(), body.ref(), body.key(), body.targets());
        return ResponseEntity.created(URI.create("/api/resources/" + result.key() + "/file")).body(result);
    }

    // --- plugins and connectors ---------------------------------------------------------------

    /** A plugin or connector of the Marketplace: what it is, what it asks, how each application would get it. Nothing runs. */
    @GetMapping("/api/skill-hub/marketplaces/{provider}/item")
    public HubInstaller.ExtensionPreview previewMarketplaceItem(@PathVariable String provider, @RequestParam String ref) {
        return installer.preview(provider, ref);
    }

    /** Installs a plugin with the application's own CLI; a declared command needs the operator's confirmation first. */
    @PostMapping("/api/skill-hub/plugins/install")
    public HubInstaller.InstallOutcome installMarketplacePlugin(@Valid @RequestBody PluginInstallRequest body) {
        return installer.installPlugin(body.provider(), body.ref(), body.target(), body.confirmation(),
                body.values() == null ? Map.of() : body.values());
    }

    /** PHASE 32 (TD-90): a plugin or a connector taken out of its application (admin). */
    @DeleteMapping("/api/skill-hub/installed/{id}")
    public HubInstaller.InstallOutcome uninstallFromApp(@PathVariable String id) {
        return installer.uninstall(id);
    }

    /** Adds a connector to the chosen applications, one outcome each. */
    @PostMapping("/api/skill-hub/connectors/install")
    public ConnectorInstallResponse installMarketplaceConnector(@Valid @RequestBody ConnectorInstallRequest body) {
        return new ConnectorInstallResponse(installer.installConnector(body.provider(), body.ref(), body.targets(), body.name(),
                body.values() == null ? Map.of() : body.values(), Boolean.TRUE.equals(body.replace())));
    }

    // --- copies of a library skill ------------------------------------------------------------

    @GetMapping("/api/skill-hub/library/{key}/copies")
    public List<SkillHubService.CopyTarget> skillCopies(@PathVariable String key) {
        return hub.copies(key);
    }

    @PostMapping("/api/skill-hub/library/{key}/copies/{app}")
    public SkillHubService.DeployResult copySkillToApp(@PathVariable String key,
                                               @PathVariable String app,
                                               @RequestBody(required = false) DeployRequest body) {
        return hub.deploy(key, app, body != null && body.replace());
    }

    /** Takes a Hub copy out of an application; the folder is kept as a backup. Admin-only, like every removal. */
    /** PHASE 32 (TD-80): ties a library skill to its source online; its updates are proposed from there. */
    @PutMapping("/api/skill-hub/library/{key}/source")
    public SkillHubService.SourceLinked linkSkillSource(@PathVariable String key, @Valid @RequestBody SourceRequest body) {
        return hub.linkSource(key, body.provider(), body.ref());
    }

    @DeleteMapping("/api/skill-hub/library/{key}/copies/{app}")
    public SkillHubService.DeployResult removeSkillCopy(@PathVariable String key, @PathVariable String app) {
        return hub.undeploy(key, app);
    }

    @GetMapping(value = "/api/skill-hub/library/{key}/package.zip", produces = "application/zip")
    public ResponseEntity<byte[]> skillPackageZip(@PathVariable String key) {
        byte[] zip = hub.zip(key);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(key + ".zip").build().toString())
                .contentType(MediaType.parseMediaType("application/zip")).body(zip);
    }

    // --- updates ---------------------------------------------------------------------------

    @GetMapping("/api/skill-hub/updates")
    public SkillUpdates.UpdatesView skillUpdates() {
        return updates.view();
    }

    /** Asks every marketplace and application for its latest state now. */
    @PostMapping("/api/skill-hub/updates/check")
    public SkillUpdates.UpdatesView checkSkillUpdates() {
        return updates.check();
    }

    /** Applies the updates named, one at a time; one failing does not stop the others. */
    @PostMapping("/api/skill-hub/updates/apply")
    public ApplyResponse applySkillUpdates(@Valid @RequestBody ApplyRequest body) {
        return new ApplyResponse(body.ids().stream().distinct().map(id -> updates.apply(id, body.overwrite())).toList());
    }
}
