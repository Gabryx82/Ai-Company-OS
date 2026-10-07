package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.controller.HarnessController.ResourceResponse;
import com.aicompany.backend.harness.model.HarnessResource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The Knowledge Hub across applications and marketplaces (PHASE 31, ADR-036):
 * what is installed where, what the marketplaces offer, what the Hub installs,
 * copies, keeps in step and updates.
 */
@RestController
public class SkillHubController {

    public record SkillHubInstalled(Instant scannedAt, Instant lastSync, long syncIntervalSeconds,
                            List<SkillHubService.AppView> apps, List<SkillHubService.SkillView> skills,
                            List<SkillCategories.Category> categories, List<String> notes, SkillHubService.SkillHubSummary summary) {
    }

    public record ImportRequest(@Size(max = 64) String key) {
    }

    public record InstallRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 500) String ref,
                                 @Size(max = 64) String key, @Size(max = 12) List<@Size(max = 40) String> targets) {
    }

    public record DeployRequest(boolean replace) {
    }

    public record ApplyRequest(@NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 120) String> ids, boolean overwrite) {
    }

    public record ApplyResponse(List<SkillUpdates.Applied> results) {
    }

    public record SkillFolderOpened(String folder) {
    }

    private final SkillHubService hub;
    private final SkillMarketplaces markets;
    private final SkillUpdates updates;
    private final SkillSync sync;

    public SkillHubController(SkillHubService hub, SkillMarketplaces markets, SkillUpdates updates, SkillSync sync) {
        this.hub = hub;
        this.markets = markets;
        this.updates = updates;
        this.sync = sync;
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
                                                  @RequestParam(required = false) List<String> provider) {
        return markets.search(q, provider);
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
