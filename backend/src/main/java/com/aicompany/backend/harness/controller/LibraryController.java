package com.aicompany.backend.harness.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.harness.controller.HarnessController.ResourceResponse;
import com.aicompany.backend.harness.exception.HarnessResourceNotFoundException;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.repository.HarnessResourceRepository;
import com.aicompany.backend.software.service.SoftwareService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Skills and knowledge as files (ADR-026): read, edit, create, re-read the folder, import from the web, open. */
@RestController
public class LibraryController {

    /** The editor the library opens in: VS Code, whose folder view shows SKILL.md and its siblings. */
    static final String EDITOR = "vscode-continue";

    private final SkillLibrary library;
    private final HarnessResourceRepository resources;
    private final SoftwareService software;

    public LibraryController(SkillLibrary library, HarnessResourceRepository resources, SoftwareService software) {
        this.library = library;
        this.resources = resources;
        this.software = software;
    }

    public record LibraryInfo(String root, boolean exists, long skills, long knowledge, String layout) {
    }

    public record FileResponse(String key, String relativePath, String absolutePath, boolean exists, String content,
                               Instant modified, long version) {
    }

    public record Content(@NotBlank @Size(max = SkillLibrary.MAX_DOCUMENT) String content) {
    }

    public record Import(@NotBlank @Size(max = 500) String url) {
    }

    public record SyncResponse(String root, List<String> created, List<String> updated, List<String> invalid) {
    }

    public record Opened(String folder, List<String> command) {
    }

    @GetMapping("/api/library")
    public LibraryInfo info() {
        List<HarnessResource> all = resources.findAll();
        return new LibraryInfo(library.root().toString(), Files.isDirectory(library.root()),
                all.stream().filter(r -> r.getKind() == HarnessResource.Kind.SKILL).count(),
                all.stream().filter(r -> r.getKind() == HarnessResource.Kind.KNOWLEDGE).count(),
                "skills/<chiave>/SKILL.md · knowledge/<chiave>.md");
    }

    @PostMapping("/api/library/sync")
    public SyncResponse sync() {
        SkillLibrary.SyncReport report = library.sync();
        return new SyncResponse(report.root().toString(), report.created(), report.updated(), report.invalid());
    }

    @PostMapping("/api/library/entries")
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody Content body) {
        HarnessResource created = library.create(body.content(), HarnessResource.Origin.USER);
        return ResponseEntity.created(URI.create("/api/resources/" + created.getKey() + "/file"))
                .body(ResourceResponse.from(created));
    }

    @PostMapping("/api/library/import")
    public ResponseEntity<ResourceResponse> importFrom(@Valid @RequestBody Import body) {
        HarnessResource created = library.importFrom(body.url());
        return ResponseEntity.created(URI.create("/api/resources/" + created.getKey() + "/file"))
                .body(ResourceResponse.from(created));
    }

    @GetMapping("/api/resources/{key}/file")
    public ResponseEntity<FileResponse> file(@PathVariable String key) {
        SkillLibrary.FileView view = library.view(key);
        long version = resources.findByKey(key).map(HarnessResource::getVersion).orElse(0L);
        return ResponseEntity.ok().eTag(ETags.of(version)).body(new FileResponse(key, view.relativePath(),
                view.absolutePath(), view.exists(), view.content(), view.modified(), version));
    }

    /** Writes the file of a catalog entry that has none yet; an existing file is left as it is. */
    @PostMapping("/api/resources/{key}/file")
    public ResponseEntity<FileResponse> materialize(@PathVariable String key) {
        library.materialize(key);
        return file(key);
    }

    @PutMapping("/api/resources/{key}/file")
    public ResponseEntity<FileResponse> save(@PathVariable String key,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             @Valid @RequestBody Content body) {
        library.save(key, body.content(), Precondition.fromHeader(ifMatch));
        return file(key);
    }

    /** Opens the entry's folder in VS Code. The folder is computed from the key, never taken from the request. */
    @PostMapping("/api/resources/{key}/open")
    public Opened open(@PathVariable String key) {
        HarnessResource resource = resources.findByKey(key)
                .orElseThrow(() -> new HarnessResourceNotFoundException("No resource '" + key + "' in the catalog"));
        library.materialize(key);
        Path file = library.root().resolve(SkillLibrary.relativePath(resource.getKind(), resource.getKey()));
        Path folder = file.getParent();
        return new Opened(folder.toString(), software.launchIn(EDITOR, folder, List.of()).command());
    }
}
