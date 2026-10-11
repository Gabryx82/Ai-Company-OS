package com.aicompany.backend.workspace.controller;

import com.aicompany.backend.workspace.service.ProjectFileBrowser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * PHASE 33 (ADR-039): the console's file explorer -- every file of a project's
 * folder, read-only. Nothing here writes, and nothing leaves the folder.
 */
@RestController
public class ProjectFilesController {

    public record ProjectFileEntry(String name, String path, ProjectFileBrowser.EntryKind kind, @Nullable Long size,
                                   @Nullable Instant modifiedAt) {

        static ProjectFileEntry from(ProjectFileBrowser.Entry e) {
            return new ProjectFileEntry(e.name(), e.path(), e.kind(), e.size(), e.modifiedAt());
        }
    }

    /** {@code path} is relative to the project folder; empty is the folder itself. */
    public record ProjectDirectoryListing(String path, List<ProjectFileEntry> entries, boolean truncated) {
    }

    /** {@code content} only for TEXT; {@code reason} says why it is not shown otherwise. */
    public record ProjectFileView(String path, String name, long size, @Nullable Instant modifiedAt,
                                  ProjectFileBrowser.ViewKind kind, @Nullable String contentType,
                                  @Nullable String content, @Nullable String reason) {
    }

    private final ProjectFileBrowser browser;

    public ProjectFilesController(ProjectFileBrowser browser) {
        this.browser = browser;
    }

    @GetMapping("/api/projects/{id}/browse")
    public ProjectDirectoryListing browse(@PathVariable Long id, @RequestParam(required = false) String path) {
        ProjectFileBrowser.Listing listing = browser.list(id, path);
        return new ProjectDirectoryListing(listing.path(),
                listing.entries().stream().map(ProjectFileEntry::from).toList(), listing.truncated());
    }

    @GetMapping("/api/projects/{id}/browse/file")
    public ProjectFileView browseFile(@PathVariable Long id, @RequestParam String path) {
        ProjectFileBrowser.View v = browser.view(id, path);
        return new ProjectFileView(v.path(), v.name(), v.size(), v.modifiedAt(), v.kind(), v.contentType(),
                v.content(), v.reason());
    }

    /** The bytes of an image the explorer shows; any other file is refused. */
    @GetMapping("/api/projects/{id}/browse/image")
    public ResponseEntity<byte[]> browseImage(@PathVariable Long id, @RequestParam String path) {
        ProjectFileBrowser.Raw raw = browser.image(id, path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(raw.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(raw.bytes());
    }
}
