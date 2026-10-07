package com.aicompany.backend.harness.hub;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.harness.exception.HarnessResourceNotFoundException;
import com.aicompany.backend.harness.hub.SkillLocations.Location;
import com.aicompany.backend.harness.hub.SkillLocations.Scope;
import com.aicompany.backend.harness.library.SkillDocument;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.repository.HarnessResourceRepository;
import com.aicompany.backend.software.service.SoftwareService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Knowledge Hub as one place (ADR-036): every skill and knowledge item on
 * this machine next to the library's, installed from a marketplace or imported
 * from an application into the library, copied from the library into the
 * applications, and kept in step -- each copy carries a {@link HubMarker}, so the
 * Hub can tell what moved where and never overwrites what it did not write.
 */
@Service
@Transactional
public class SkillHubService {

    public static final String LIBRARY_APP = "aicos";
    private static final String LIBRARY_LOCATION = "aicos.library";
    private static final Pattern APP_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final int MAX_CONTENT = 200_000;

    // --- views --------------------------------------------------------------------------

    public record AgentRef(Long id, String name, String target) {
    }

    public record LocationView(String id, String label, Scope scope, String path, boolean exists, boolean writable,
                               List<String> readers, int count) {
    }

    public record AppView(String id, String name, String description, boolean detected, String software,
                          List<String> targets, List<AgentRef> agents, List<LocationView> locations, int count,
                          boolean installable) {
    }

    public record ProvenanceView(String provider, String providerName, String reference, String sourceUrl,
                                 String installedVersion, String latestVersion, Instant installedAt, Instant checkedAt,
                                 boolean updateAvailable, boolean localChanges, String checkError) {
    }

    public record CopyView(String app, String location, String skillId, String path, CopyStatus status, Instant deployedAt) {
    }

    /**
     * A skill as the console lists it -- from an application, or an entry of the
     * library ({@code app = "aicos"}, {@code id = "aicos:<key>"}).
     *
     * @param libraryKey the library entry this is (library), a copy of (Hub copy) or was imported as
     * @param copyStatus for a Hub copy: how it stands against the library
     */
    public record SkillView(String id, String app, String location, Scope scope, String kind, String name,
                            String description, String category, String categoryLabel, String plugin,
                            String pluginVersion, String marketplace, Boolean enabled, String version, String path,
                            boolean writable, Instant modified, String libraryKey, CopyStatus copyStatus,
                            ProvenanceView provenance, List<CopyView> copies, boolean updateAvailable) {
    }

    public record SkillHubSummary(int total, int apps, int libraryEntries, int updates, int copies) {
    }

    public record InstalledView(Instant scannedAt, List<AppView> apps, List<SkillView> skills,
                                List<SkillCategories.Category> categories, List<String> notes, SkillHubSummary summary) {
    }

    public record FoundDetail(SkillView skill, String content, boolean truncated, List<SkillMarketplaces.PackageFile> files,
                              String folder) {
    }

    /** How a Hub copy stands against the library. */
    public enum CopyStatus {
        /** Copy and library as they were when the Hub wrote the copy. */
        IN_SYNC,
        /** The library changed since: the copy can be brought up to date. */
        OUTDATED,
        /** The copy was changed inside the application: it can be brought into the library. */
        MODIFIED,
        /** Both changed: choosing a side overwrites the other (a backup stays). */
        CONFLICT,
        /** The library no longer has this entry. */
        ORPHAN
    }

    public record CopyTarget(String app, String appName, boolean available, String path, String status,
                             Instant deployedAt, String skillId) {
    }

    public record DeployResult(String app, String outcome, String path, String backup, String message) {
    }

    public record InstallResult(String key, String name, String filePath, List<DeployResult> copies) {
    }

    private final SkillLocations locations;
    private final SkillScanner scanner;
    private final SkillMarketplaces markets;
    private final SkillLibrary library;
    private final HarnessResourceRepository resources;
    private final SkillInstallationRepository installations;
    private final HubFiles files;
    private final AgentRepository agents;
    private final SoftwareService software;

    public SkillHubService(SkillLocations locations, SkillScanner scanner, SkillMarketplaces markets, SkillLibrary library,
                           HarnessResourceRepository resources, SkillInstallationRepository installations, HubFiles files,
                           AgentRepository agents, SoftwareService software) {
        this.locations = locations;
        this.scanner = scanner;
        this.markets = markets;
        this.library = library;
        this.resources = resources;
        this.installations = installations;
        this.files = files;
        this.agents = agents;
        this.software = software;
    }

    // --- the unified view -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InstalledView installed(boolean rescan) {
        SkillScanner.Scan scan = rescan ? scanner.scan() : scanner.last();
        Map<String, String> libraryPrints = new HashMap<>();
        Function<String, String> libraryPrint = key -> libraryPrints.computeIfAbsent(key, this::libraryFingerprintOrNull);
        Map<Long, SkillInstallation> provenance = installations.findAll().stream()
                .collect(Collectors.toMap(SkillInstallation::getResourceId, Function.identity(), (a, b) -> a));
        List<HarnessResource> entries = resources.findAll().stream().filter(r -> SkillLibrary.fileBacked(r.getKind()))
                .sorted(Comparator.comparing(HarnessResource::getName, String.CASE_INSENSITIVE_ORDER)).toList();

        // Which found skills a library entry came from (provider 'app').
        Map<String, String> importedAs = new HashMap<>();
        for (HarnessResource entry : entries) {
            SkillInstallation p = provenance.get(entry.getId());
            if (p != null && SkillInstallation.APP.equals(p.getProvider())) {
                importedAs.put(p.getReference(), entry.getKey());
            }
        }

        List<SkillView> found = new ArrayList<>();
        Map<String, List<CopyView>> copiesByKey = new HashMap<>();
        for (FoundSkill skill : scan.skills()) {
            CopyStatus status = skill.marker() == null ? null : copyStatus(skill, libraryPrint);
            if (status != null) {
                copiesByKey.computeIfAbsent(skill.marker().key(), k -> new ArrayList<>())
                        .add(new CopyView(skill.app(), skill.location(), skill.id(), skill.folder().toString(), status,
                                skill.marker().deployedAt()));
            }
            String key = skill.marker() != null ? skill.marker().key()
                    : importedAs.get(skill.location() + ":" + skill.relativePath());
            found.add(view(skill, key, status));
        }

        List<SkillView> libraryViews = new ArrayList<>();
        for (HarnessResource entry : entries) {
            SkillInstallation p = provenance.get(entry.getId());
            ProvenanceView pv = p == null ? null : provenanceView(p, entry, libraryPrint);
            String description = entry.getDescription();
            SkillCategories.Category category = entry.getKind() == HarnessResource.Kind.KNOWLEDGE ? SkillScanner.INSTRUCTIONS
                    : SkillCategories.of(entry.getName() + " " + entry.getKey(), null, description);
            String path = entry.getFilePath() == null ? null : library.root().resolve(entry.getFilePath()).toString();
            List<CopyView> copies = copiesByKey.getOrDefault(entry.getKey(), List.of());
            libraryViews.add(new SkillView(LIBRARY_APP + ":" + entry.getKey(), LIBRARY_APP, LIBRARY_LOCATION, Scope.LIBRARY,
                    entry.getKind().name(), entry.getName(), description, category.key(), category.label(), null, null,
                    null, null, pv == null ? null : pv.installedVersion(), path, true, null, entry.getKey(), null, pv,
                    copies, pv != null && pv.updateAvailable()));
        }

        List<SkillView> all = new ArrayList<>(libraryViews);
        all.addAll(found);
        List<Agent> everyone = agents.findAllByOrderByIdAsc().stream().filter(Agent::isActive).toList();
        List<AppView> apps = new ArrayList<>();
        apps.add(new AppView(LIBRARY_APP, "AI Company OS", "La libreria di AI Company OS: skill e knowledge che gli agenti ricevono",
                true, null, List.of("engine"), agentsOf(everyone, List.of("engine")),
                List.of(new LocationView(LIBRARY_LOCATION, "Libreria", Scope.LIBRARY, library.root().toString(),
                        Files.isDirectory(library.root()), true, List.of(LIBRARY_APP), libraryViews.size())),
                libraryViews.size(), true));
        for (SkillLocations.App app : locations.apps()) {
            List<LocationView> locationViews = locations.locations().stream().filter(l -> l.app().equals(app.id()))
                    .map(l -> new LocationView(l.id(), l.label(), l.scope(), l.root().toString(), Files.exists(l.root()),
                            l.writable(), l.readers(), (int) found.stream().filter(s -> s.location().equals(l.id())).count()))
                    .toList();
            apps.add(new AppView(app.id(), app.name(), app.description(), app.detected(), app.software(), app.targets(),
                    agentsOf(everyone, app.targets()), locationViews,
                    (int) found.stream().filter(s -> s.app().equals(app.id())).count(),
                    locations.deployTarget(app.id()).isPresent()));
        }
        int updates = (int) all.stream().filter(SkillView::updateAvailable).count()
                + (int) found.stream().filter(s -> s.copyStatus() != null && s.copyStatus() != CopyStatus.IN_SYNC
                && s.copyStatus() != CopyStatus.ORPHAN).count();
        int copies = (int) found.stream().filter(s -> s.copyStatus() != null).count();
        SkillHubSummary summary = new SkillHubSummary(all.size(), (int) apps.stream().filter(a -> a.detected() && a.count() > 0).count(),
                libraryViews.size(), updates, copies);
        List<SkillCategories.Category> categories = new ArrayList<>(SkillCategories.all());
        categories.add(SkillScanner.INSTRUCTIONS);
        return new InstalledView(scan.at(), apps, all, categories, scan.notes(), summary);
    }

    @Transactional(readOnly = true)
    public FoundDetail detail(String id) {
        FoundSkill skill = found(id);
        CopyStatus status = skill.marker() == null ? null : copyStatus(skill, this::libraryFingerprintOrNull);
        String importedKey = installations.findAll().stream()
                .filter(p -> SkillInstallation.APP.equals(p.getProvider())
                        && p.getReference().equals(skill.location() + ":" + skill.relativePath()))
                .findFirst().flatMap(p -> resources.findById(p.getResourceId())).map(HarnessResource::getKey).orElse(null);
        String content;
        boolean truncated;
        try {
            String text = Files.readString(skill.file(), StandardCharsets.UTF_8);
            truncated = text.length() > MAX_CONTENT;
            content = truncated ? text.substring(0, MAX_CONTENT) : text;
        } catch (IOException e) {
            throw HubProblemException.notFound("The file of '" + skill.name() + "' could not be read: " + e.getMessage());
        }
        List<SkillMarketplaces.PackageFile> list = new ArrayList<>();
        if ("SKILL".equals(skill.kind())) {
            for (Path file : Fingerprints.files(skill.folder())) {
                try {
                    list.add(new SkillMarketplaces.PackageFile(Fingerprints.relative(skill.folder(), file), Files.size(file)));
                } catch (IOException e) {
                    list.add(new SkillMarketplaces.PackageFile(Fingerprints.relative(skill.folder(), file), -1));
                }
            }
        }
        return new FoundDetail(view(skill, skill.marker() != null ? skill.marker().key() : importedKey, status), content,
                truncated, list, skill.folder().toString());
    }

    /** Opens a found skill's folder in VS Code. The folder comes from the scan, inside a known location. */
    @Transactional(readOnly = true)
    public String open(String id) {
        FoundSkill skill = found(id);
        software.launchIn(com.aicompany.backend.harness.controller.LibraryController.EDITOR, skill.folder(), List.of());
        return skill.folder().toString();
    }

    // --- into the library -------------------------------------------------------------------

    /**
     * Copies a skill found in an application into the library, as a new entry
     * that remembers where it came from -- so a later change at the source shows
     * up as an update. A global instructions file or a knowledge item becomes a
     * knowledge entry.
     */
    public HarnessResource importFound(String id, String requestedKey) {
        FoundSkill skill = found(id);
        if (skill.marker() != null) {
            throw HubProblemException.conflict("È una copia della skill «" + skill.marker().key()
                    + "» della libreria, scritta dal Knowledge Hub: è già nella libreria");
        }
        String reference = skill.location() + ":" + skill.relativePath();
        String key = keyFor(requestedKey, skill.name());
        if ("KNOWLEDGE".equals(skill.kind())) {
            String text = read(skill.file());
            String body = Frontmatter.body(text).strip();
            String content = new SkillDocument(key, truncate(skill.name(), 120), HarnessResource.Kind.KNOWLEDGE,
                    skill.description() == null ? null : truncate(skill.description(), 2000),
                    List.of(skill.app()), null, body.isEmpty() ? text : body).render();
            HarnessResource created = library.create(content, HarnessResource.Origin.FILE);
            installations.save(new SkillInstallation(created.getId(), SkillInstallation.APP, reference,
                    skill.file().toString(), null, Fingerprints.of(Map.of(skill.file().getFileName().toString(), bytes(skill.file()))),
                    Fingerprints.sha256(content)));
            return created;
        }
        Map<String, byte[]> source = readFolder(skill.folder());
        Map<String, byte[]> forLibrary = forLibrary(key, source, skill.name(), skill.description());
        Path folder = library.skillFolder(key);
        requireFreeLibraryFolder(key, folder);
        files.write(folder, forLibrary, "libreria-" + key);
        HarnessResource created = library.indexFolder(key, HarnessResource.Origin.FILE, null);
        installations.save(new SkillInstallation(created.getId(), SkillInstallation.APP, reference,
                skill.folder().toString(), skill.version(), Fingerprints.of(source), Fingerprints.of(forLibrary)));
        return created;
    }

    /** Installs a marketplace package into the library, then copies it into the applications named. */
    public InstallResult install(String provider, String ref, String requestedKey, List<String> targets) {
        SkillMarketplaces.Package pkg = markets.fetch(provider, ref);
        for (SkillInstallation existing : installations.findByProviderAndReference(pkg.provider(), pkg.reference())) {
            String key = resources.findById(existing.getResourceId()).map(HarnessResource::getKey).orElse("?");
            throw HubProblemException.conflict("Questa skill è già installata nella libreria come «" + key
                    + "»: per una versione nuova usa Aggiornamenti");
        }
        Map<String, String> front = Frontmatter.read(new String(pkg.files().get("SKILL.md"), StandardCharsets.UTF_8));
        String key = keyFor(requestedKey, Optional.ofNullable(front.get("name")).orElse(pkg.name()));
        Map<String, byte[]> forLibrary = forLibrary(key, pkg.files(), pkg.name(), pkg.description());
        Path folder = library.skillFolder(key);
        requireFreeLibraryFolder(key, folder);
        files.write(folder, forLibrary, "libreria-" + key);
        HarnessResource created = library.indexFolder(key, HarnessResource.Origin.WEB, pkg.sourceUrl());
        installations.save(new SkillInstallation(created.getId(), pkg.provider(), pkg.reference(), pkg.sourceUrl(),
                pkg.version(), pkg.fingerprint(), Fingerprints.of(forLibrary)));
        List<DeployResult> copies = new ArrayList<>();
        for (String app : targets == null ? List.<String>of() : targets.stream().distinct().toList()) {
            try {
                copies.add(deploy(key, app, false));
            } catch (HubProblemException e) {
                copies.add(new DeployResult(app, "FAILED", null, null, e.getMessage()));
            }
        }
        return new InstallResult(created.getKey(), created.getName(), created.getFilePath(), copies);
    }

    // --- copies in the applications ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CopyTarget> copies(String key) {
        HarnessResource entry = librarySkill(key);
        String libraryPrint = libraryFingerprintOrNull(entry.getKey());
        List<CopyTarget> out = new ArrayList<>();
        for (SkillLocations.App app : locations.apps()) {
            Optional<Location> target = locations.locations().stream()
                    .filter(l -> l.app().equals(app.id()) && l.writable() && l.scope() == Scope.PERSONAL).findFirst();
            if (target.isEmpty()) {
                continue;
            }
            Path dest = target.get().root().resolve(key);
            String status = "NONE";
            Instant at = null;
            String skillId = null;
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                Optional<HubMarker> marker = HubMarker.read(dest).filter(m -> m.key().equals(key));
                if (marker.isPresent()) {
                    status = copyStatus(marker.get(), dest, libraryPrint).name();
                    at = marker.get().deployedAt();
                } else {
                    status = "FOREIGN";
                }
                skillId = FoundSkill.idOf(target.get().id(), key + "/SKILL.md");
            }
            out.add(new CopyTarget(app.id(), app.name(), app.detected(), dest.toString(), status, at, skillId));
        }
        return out;
    }

    /**
     * Copies a library skill into an application's personal folder. A folder the
     * Hub did not write, or a copy changed inside the application, is overwritten
     * only with {@code replace} -- and then kept as a backup.
     */
    public DeployResult deploy(String key, String appId, boolean replace) {
        HarnessResource entry = librarySkill(key);
        Location target = locations.deployTarget(appId).orElseThrow(() -> appId.equals("claude-desktop")
                ? HubProblemException.readOnly("Claude Desktop riceve le skill dal tuo account: scarica il pacchetto .zip "
                + "e caricalo da Impostazioni → Capacità → Skill")
                : HubProblemException.readOnly("«" + appId + "» non è su questo computer o non ha una cartella di skill personali"));
        Path source = library.skillFolder(entry.getKey());
        Map<String, byte[]> libraryFiles = readFolder(source);
        String libraryPrint = Fingerprints.of(libraryFiles);
        Map<String, byte[]> rendered = renderForApp(entry, libraryFiles);
        String copyPrint = Fingerprints.of(rendered);
        Path dest = target.root().resolve(entry.getKey());
        String outcome = "CREATED";
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            Optional<HubMarker> marker = HubMarker.read(dest).filter(m -> m.key().equals(entry.getKey()));
            if (marker.isEmpty()) {
                if (!replace) {
                    throw HubProblemException.conflict("In " + locations.app(appId).name() + " esiste già una skill «"
                            + entry.getKey() + "» che non è stata installata dal Knowledge Hub (" + dest + ")");
                }
            } else {
                String current = Fingerprints.content(dest);
                boolean changedThere = !current.equals(marker.get().copy());
                if (changedThere && !replace) {
                    throw HubProblemException.conflict("La copia in " + locations.app(appId).name()
                            + " è stata modificata nell'app dopo l'installazione: riportala nella libreria o confermala sovrascrittura");
                }
                if (!changedThere && current.equals(copyPrint) && marker.get().library().equals(libraryPrint)) {
                    return new DeployResult(appId, "UNCHANGED", dest.toString(), null, "Già aggiornata");
                }
                outcome = "UPDATED";
            }
        }
        Map<String, byte[]> withMarker = new TreeMap<>(rendered);
        withMarker.put(Fingerprints.MARKER, new HubMarker(entry.getKey(), libraryPrint, copyPrint, Instant.now()).json());
        HubFiles.Swap swap = files.write(dest, withMarker, appId + "-" + entry.getKey());
        return new DeployResult(appId, outcome, dest.toString(), swap.backup().map(Path::toString).orElse(null),
                outcome.equals("CREATED") ? "Installata in " + locations.app(appId).name()
                        : "Aggiornata in " + locations.app(appId).name());
    }

    /** Removes a Hub copy from an application: the folder goes to the backups, never to nothing. */
    public DeployResult undeploy(String key, String appId) {
        Location target = locations.deployTarget(appId)
                .orElseThrow(() -> HubProblemException.notFound("«" + appId + "» has no personal skill folder here"));
        Path dest = target.root().resolve(librarySkillKey(key));
        if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            throw HubProblemException.notFound("Nessuna copia di «" + key + "» in " + locations.app(appId).name());
        }
        if (HubMarker.read(dest).filter(m -> m.key().equals(key)).isEmpty()) {
            throw HubProblemException.readOnly("La cartella " + dest + " non è stata scritta dal Knowledge Hub: "
                    + "si toglie dall'app, non da qui");
        }
        Path backup = files.backup(dest, appId + "-" + key);
        return new DeployResult(appId, "REMOVED", dest.toString(), backup.toString(),
                "Tolta da " + locations.app(appId).name() + "; una copia è in " + backup);
    }

    /** The skill as a .zip with its folder at the root, the shape Claude Desktop and claude.ai upload. */
    @Transactional(readOnly = true)
    public byte[] zip(String key) {
        HarnessResource entry = librarySkill(key);
        Map<String, byte[]> rendered = renderForApp(entry, readFolder(library.skillFolder(entry.getKey())));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : rendered.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey() + "/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // --- in step: pull a changed copy, re-import a changed source, reinstall a package -------------

    /** Brings a copy changed inside an application into the library; the other untouched copies follow. */
    public String pull(String skillId, boolean overwrite) {
        FoundSkill copy = found(skillId);
        if (copy.marker() == null) {
            throw HubProblemException.notFound("This skill is not a Hub copy");
        }
        HarnessResource entry = librarySkill(copy.marker().key());
        Path folder = library.skillFolder(entry.getKey());
        Map<String, byte[]> current = readFolder(folder);
        if (!Fingerprints.of(current).equals(copy.marker().library()) && !overwrite) {
            throw HubProblemException.conflict("Anche la libreria è cambiata da quando la copia è stata scritta: "
                    + "conferma per sovrascriverla (resterà un backup)");
        }
        Map<String, byte[]> fromCopy = readFolder(copy.folder());
        Map<String, byte[]> merged = mergeIntoLibrary(entry, current, fromCopy);
        files.write(folder, merged, "libreria-" + entry.getKey());
        library.reindexFolder(entry.getKey());
        String libraryPrint = Fingerprints.of(merged);
        installations.findByResourceId(entry.getId()).ifPresent(p -> p.adoptedLocally(libraryPrint));
        // The copy it came from is now in step with the library.
        rewriteMarker(copy.folder(), entry.getKey(), libraryPrint);
        int followed = propagate(entry.getKey(), copy.id());
        return "«" + entry.getName() + "» aggiornata nella libreria da " + locations.app(copy.app()).name()
                + (followed > 0 ? "; aggiornate anche " + followed + " altre copie" : "");
    }

    /** Re-installs a package at its latest upstream state, or re-imports a changed application skill. */
    public String update(String key, boolean overwrite) {
        HarnessResource entry = resources.findByKey(key)
                .orElseThrow(() -> new HarnessResourceNotFoundException("No resource '" + key + "' in the catalog"));
        SkillInstallation provenance = installations.findByResourceId(entry.getId())
                .orElseThrow(() -> HubProblemException.notFound("«" + key + "» non è stata installata dal Knowledge Hub"));
        if (entry.getKind() == HarnessResource.Kind.KNOWLEDGE) {
            throw HubProblemException.readOnly("Una knowledge importata si aggiorna reimportandola");
        }
        Path folder = library.skillFolder(key);
        String localNow = Fingerprints.of(readFolder(folder));
        if (!localNow.equals(provenance.getLocalFingerprint()) && !overwrite) {
            throw HubProblemException.conflict("«" + entry.getName() + "» è stata modificata nella libreria dopo l'installazione: "
                    + "conferma per sovrascriverla (resterà un backup)");
        }
        Map<String, byte[]> incoming;
        String version;
        String upstream;
        if (SkillInstallation.APP.equals(provenance.getProvider())) {
            FoundSkill source = sourceOf(provenance, scanner.scan())
                    .orElseThrow(() -> HubProblemException.notFound("La skill da cui «" + key + "» è stata importata non c'è più"));
            incoming = readFolder(source.folder());
            version = source.version();
            upstream = Fingerprints.of(incoming);
        } else {
            SkillMarketplaces.Package pkg = markets.fetch(provenance.getProvider(), provenance.getReference());
            incoming = pkg.files();
            version = pkg.version();
            upstream = pkg.fingerprint();
        }
        Map<String, byte[]> forLibrary = forLibrary(key, incoming, entry.getName(), entry.getDescription());
        files.write(folder, forLibrary, "libreria-" + key);
        library.reindexFolder(key);
        provenance.reinstalled(version, upstream, Fingerprints.of(forLibrary));
        int followed = propagate(key, null);
        return "«" + entry.getName() + "» aggiornata" + (version == null ? "" : " alla versione " + version)
                + (followed > 0 ? "; aggiornate anche " + followed + " copie nelle app" : "");
    }

    /** Asks upstream for the latest state of every package the Hub installed. Network; one failure stays on its row. */
    public List<String> checkProvenance() {
        List<String> errors = new ArrayList<>();
        SkillScanner.Scan scan = scanner.last();
        for (SkillInstallation p : installations.findAll()) {
            try {
                if (SkillInstallation.APP.equals(p.getProvider())) {
                    Optional<FoundSkill> source = sourceOf(p, scan);
                    if (source.isEmpty()) {
                        p.checkFailed("La fonte non c'è più");
                    } else {
                        p.checked(source.get().version(), "KNOWLEDGE".equals(source.get().kind())
                                ? Fingerprints.of(Map.of(source.get().file().getFileName().toString(), bytes(source.get().file())))
                                : Fingerprints.of(readFolder(source.get().folder())));
                    }
                } else {
                    SkillMarketplaces.Latest latest = markets.latest(p.getProvider(), p.getReference());
                    p.checked(latest.version(), latest.fingerprint());
                }
            } catch (RuntimeException e) {
                p.checkFailed(e.getMessage());
                errors.add(p.getReference() + ": " + e.getMessage());
            }
        }
        return errors;
    }

    // --- internals --------------------------------------------------------------------------

    private SkillView view(FoundSkill s, String libraryKey, CopyStatus status) {
        return new SkillView(s.id(), s.app(), s.location(), s.scope(), s.kind(), s.name(), s.description(),
                s.category().key(), s.category().label(), s.plugin(), s.pluginVersion(), s.marketplace(), s.enabled(),
                s.version(), s.file().toString(), s.writable(), s.modified(), libraryKey, status, null, List.of(),
                status == CopyStatus.OUTDATED || status == CopyStatus.MODIFIED || status == CopyStatus.CONFLICT);
    }

    private ProvenanceView provenanceView(SkillInstallation p, HarnessResource entry, Function<String, String> libraryPrint) {
        String local = entry.getKind() == HarnessResource.Kind.SKILL ? libraryPrint.apply(entry.getKey()) : null;
        String providerName = SkillInstallation.APP.equals(p.getProvider()) ? "App: " + appOfReference(p.getReference())
                : markets.providers().stream().filter(m -> m.key().equals(p.getProvider())).map(SkillMarketplaces.Provider::name)
                .findFirst().orElse(p.getProvider());
        return new ProvenanceView(p.getProvider(), providerName, p.getReference(), p.getSourceUrl(), p.getInstalledVersion(),
                p.getLatestVersion(), p.getInstalledAt(), p.getCheckedAt(), p.updateAvailable(),
                local != null && !local.equals(p.getLocalFingerprint()), p.getCheckError());
    }

    private String appOfReference(String reference) {
        String location = reference.substring(0, Math.max(0, reference.indexOf(':')));
        return locations.locations().stream().filter(l -> l.id().equals(location)).findFirst()
                .map(l -> locations.app(l.app()).name()).orElse(location);
    }

    private Optional<FoundSkill> sourceOf(SkillInstallation p, SkillScanner.Scan scan) {
        return scan.skills().stream().filter(s -> (s.location() + ":" + s.relativePath()).equals(p.getReference())).findFirst();
    }

    private CopyStatus copyStatus(FoundSkill copy, Function<String, String> libraryPrint) {
        return copyStatus(copy.marker(), copy.folder(), libraryPrint.apply(copy.marker().key()));
    }

    private static CopyStatus copyStatus(HubMarker marker, Path folder, String libraryPrint) {
        if (libraryPrint == null) {
            return CopyStatus.ORPHAN;
        }
        boolean libraryChanged = !libraryPrint.equals(marker.library());
        boolean copyChanged = !Fingerprints.content(folder).equals(marker.copy());
        if (libraryChanged && copyChanged) {
            return CopyStatus.CONFLICT;
        }
        return libraryChanged ? CopyStatus.OUTDATED : copyChanged ? CopyStatus.MODIFIED : CopyStatus.IN_SYNC;
    }

    /** Redeploys every copy of a library skill that nobody changed inside its application. */
    private int propagate(String key, String exceptSkillId) {
        String libraryPrint = libraryFingerprintOrNull(key);
        int done = 0;
        for (FoundSkill copy : scanner.scan().skills()) {
            if (copy.marker() == null || !copy.marker().key().equals(key) || copy.id().equals(exceptSkillId)) {
                continue;
            }
            if (copyStatus(copy.marker(), copy.folder(), libraryPrint) == CopyStatus.OUTDATED) {
                deploy(key, copy.app(), false);
                done++;
            }
        }
        return done;
    }

    private void rewriteMarker(Path folder, String key, String libraryPrint) {
        try {
            String copyPrint = Fingerprints.content(folder);
            new HubMarker(key, libraryPrint, copyPrint, Instant.now()).write(folder);
        } catch (IOException e) {
            throw HubProblemException.conflict("Il marcatore della copia in " + folder + " non si può scrivere: " + e.getMessage());
        }
    }

    /**
     * A copy's files back into the library. When the library's SKILL.md is the
     * applications' shape the copy is taken as it is; otherwise the copy was
     * rendered ({@link #renderForApp}) and only its description and instructions
     * come back, under the library's own frontmatter.
     */
    private Map<String, byte[]> mergeIntoLibrary(HarnessResource entry, Map<String, byte[]> library,
                                                 Map<String, byte[]> copy) {
        Map<String, byte[]> out = new TreeMap<>(copy);
        out.remove(Fingerprints.MARKER);
        String libraryMd = new String(library.getOrDefault("SKILL.md", new byte[0]), StandardCharsets.UTF_8);
        if (!appCompatible(libraryMd)) {
            String copyMd = new String(copy.getOrDefault("SKILL.md", new byte[0]), StandardCharsets.UTF_8);
            Map<String, String> copyFront = Frontmatter.read(copyMd);
            SkillDocument current = SkillDocument.parse(libraryMd, SkillDocument.declaresKey(libraryMd) ? null : entry.getKey());
            String description = Optional.ofNullable(copyFront.get("description")).orElse(current.description());
            out.put("SKILL.md", new SkillDocument(current.key(), current.name(), current.kind(), description,
                    current.tags(), current.source(), Frontmatter.body(copyMd).strip()).render().getBytes(StandardCharsets.UTF_8));
        }
        SkillLibrary.validate(new String(out.get("SKILL.md"), StandardCharsets.UTF_8), entry.getKey());
        return out;
    }

    /**
     * The skill as an application reads it. A library file whose frontmatter is
     * already the standard one ({@code name} as an identifier, a {@code description})
     * goes as it is; a library-style one gets {@code name} = the key and its title
     * under {@code metadata}, with quoted values a strict YAML reader accepts.
     */
    static Map<String, byte[]> renderForApp(HarnessResource entry, Map<String, byte[]> libraryFiles) {
        Map<String, byte[]> out = new TreeMap<>(libraryFiles);
        out.remove(Fingerprints.MARKER);
        String md = new String(libraryFiles.getOrDefault("SKILL.md", new byte[0]), StandardCharsets.UTF_8);
        if (appCompatible(md)) {
            return out;
        }
        Map<String, String> front = Frontmatter.read(md);
        String description = Optional.ofNullable(front.get("description")).filter(d -> !d.isBlank())
                .orElse(Optional.ofNullable(entry.getDescription()).orElse(entry.getName()));
        String title = Optional.ofNullable(front.get("name")).orElse(entry.getName());
        String rendered = "---\n"
                + "name: " + entry.getKey() + "\n"
                + "description: " + yamlString(truncate(description.replace('\n', ' '), 1024)) + "\n"
                + "metadata:\n"
                + "  title: " + yamlString(title) + "\n"
                + "  aicos-key: " + entry.getKey() + "\n"
                + "---\n\n"
                + Frontmatter.body(md).strip() + "\n";
        out.put("SKILL.md", rendered.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    static boolean appCompatible(String skillMd) {
        Map<String, String> front = Frontmatter.read(skillMd);
        String name = front.get("name");
        String description = front.get("description");
        return name != null && APP_NAME.matcher(name).matches() && description != null && !description.isBlank()
                && !front.containsKey("key") && !front.containsKey("kind");
    }

    /**
     * A package as the library keeps it: the files as they came, with a SKILL.md
     * the library accepts -- given a frontmatter when it has none, and a name
     * when its frontmatter lacks one.
     */
    static Map<String, byte[]> forLibrary(String key, Map<String, byte[]> files, String name, String description) {
        Map<String, byte[]> out = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().equals(Fingerprints.MARKER) || PackagePaths.hidden(file.getKey())) {
                continue;
            }
            PackagePaths.require(file.getKey());
            total += file.getValue().length;
            PackagePaths.requireBudget(out.size() + 1, total);
            out.put(file.getKey(), file.getValue());
        }
        byte[] md = out.get("SKILL.md");
        if (md == null) {
            throw HubProblemException.packageRefused("The package has no SKILL.md at its root");
        }
        String text = new String(md, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (Frontmatter.block(text).isEmpty()) {
            text = "---\nname: " + yamlString(truncate(Optional.ofNullable(name).orElse(key), 120)) + "\n"
                    + (description == null ? "" : "description: " + yamlString(truncate(description.replace('\n', ' '), 1024)) + "\n")
                    + "---\n\n" + text;
        } else if (Frontmatter.read(text).get("name") == null) {
            text = "---\nname: " + yamlString(truncate(Optional.ofNullable(name).orElse(key), 120)) + "\n" + text.substring(4);
        }
        SkillLibrary.validate(text, key);
        out.put("SKILL.md", text.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private void requireFreeLibraryFolder(String key, Path folder) {
        if (resources.existsByKey(key)) {
            throw HubProblemException.conflict("La libreria ha già una voce «" + key + "»: scegli un'altra chiave");
        }
        if (Files.isRegularFile(folder.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
            throw HubProblemException.conflict("La cartella " + folder + " ha già un SKILL.md: usa «Rileggi cartella» "
                    + "per indicizzarlo, o scegli un'altra chiave");
        }
    }

    private String keyFor(String requested, String name) {
        String key = requested != null && !requested.isBlank() ? requested.strip() : SkillLibrary.slug(name);
        if (key == null || !SkillDocument.KEY.matcher(key).matches()) {
            throw HubProblemException.packageRefused("La chiave deve avere 2-64 lettere minuscole, cifre o trattini");
        }
        return key;
    }

    private HarnessResource librarySkill(String key) {
        HarnessResource entry = resources.findByKey(key)
                .orElseThrow(() -> new HarnessResourceNotFoundException("No resource '" + key + "' in the catalog"));
        if (entry.getKind() != HarnessResource.Kind.SKILL) {
            throw HubProblemException.readOnly("Solo le skill si copiano nelle app: «" + key + "» è " + entry.getKind());
        }
        if (!Files.isRegularFile(library.skillFolder(key).resolve("SKILL.md"))) {
            throw HubProblemException.notFound("«" + key + "» non ha ancora un file: crealo dal Knowledge Hub");
        }
        return entry;
    }

    private String librarySkillKey(String key) {
        if (key == null || !SkillDocument.KEY.matcher(key).matches()) {
            throw HubProblemException.notFound("Not a skill key: " + key);
        }
        return key;
    }

    private String libraryFingerprintOrNull(String key) {
        Path folder;
        try {
            folder = library.skillFolder(key);
        } catch (RuntimeException e) {
            return null;
        }
        return Files.isRegularFile(folder.resolve("SKILL.md")) && resources.existsByKey(key) ? Fingerprints.content(folder) : null;
    }

    private FoundSkill found(String id) {
        return scanner.find(id).orElseThrow(() -> HubProblemException.notFound("No skill '" + id + "' on this machine"));
    }

    private static List<AgentRef> agentsOf(List<Agent> everyone, List<String> targets) {
        return everyone.stream().filter(a -> targets.contains(a.getExecutionTarget()))
                .map(a -> new AgentRef(a.getId(), a.getName(), a.getExecutionTarget())).toList();
    }

    private static Map<String, byte[]> readFolder(Path folder) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        for (Path file : Fingerprints.files(folder)) {
            String relative = Fingerprints.relative(folder, file);
            byte[] bytes = bytes(file);
            total += bytes.length;
            PackagePaths.requireBudget(out.size() + 1, total);
            out.put(relative, bytes);
        }
        return out;
    }

    private static byte[] bytes(Path file) {
        try {
            if (Files.size(file) > PackagePaths.MAX_FILE) {
                throw HubProblemException.packageRefused("Il file " + file.getFileName() + " supera 1 MB");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw HubProblemException.notFound("The file " + file + " could not be read: " + e.getMessage());
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    static String yamlString(String value) {
        return "\"" + Objects.requireNonNullElse(value, "").replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\t", " ") + "\"";
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
