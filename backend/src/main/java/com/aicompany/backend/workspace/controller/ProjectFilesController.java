package com.aicompany.backend.workspace.controller;

import com.aicompany.backend.deletion.DeletionPreview;
import com.aicompany.backend.workspace.service.ProjectFileBrowser;
import com.aicompany.backend.workspace.service.ProjectFileOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * PHASE 33 (ADR-039): the console's file explorer -- every file of a project's
 * folder. Nothing leaves the folder.
 *
 * <p>ADR-040: the context menu's writes -- rename, a new file or folder, the Recycle
 * Bin -- and an Explorer window. One item at a time, never over a name that exists,
 * never destroyed.
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

    /** ADR-040: a new name in the same folder; never a path. */
    public record ProjectFileRename(@NotBlank @Size(max = 1000) String path, @NotBlank @Size(max = 255) String name) {
    }

    /** ADR-040: an empty file or folder; {@code parent} empty or absent is the project folder. */
    public record ProjectFileCreate(@Size(max = 1000) String parent, @NotBlank @Size(max = 255) String name,
                                    @NotNull ProjectFileBrowser.EntryKind kind) {
    }

    /** ADR-040: what Explorer shows; {@code path} empty or absent is the project folder. */
    public record ProjectFileReveal(@Size(max = 1000) String path) {
    }

    private final ProjectFileBrowser browser;
    private final ProjectFileOperations operations;

    public ProjectFilesController(ProjectFileBrowser browser, ProjectFileOperations operations) {
        this.browser = browser;
        this.operations = operations;
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

    // --- ADR-040: the context menu ----------------------------------------------------

    @PostMapping("/api/projects/{id}/browse/rename")
    public ProjectFileEntry renameEntry(@PathVariable Long id, @Valid @RequestBody ProjectFileRename body) {
        return ProjectFileEntry.from(operations.rename(id, body.path(), body.name()));
    }

    @PostMapping("/api/projects/{id}/browse/create")
    public ResponseEntity<ProjectFileEntry> createEntry(@PathVariable Long id, @Valid @RequestBody ProjectFileCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectFileEntry.from(operations.create(id, body.parent(), body.name(), body.kind())));
    }

    /** What moving the item to the Recycle Bin would do, and what stops it. */
    @GetMapping("/api/projects/{id}/browse/deletion")
    public DeletionPreview entryDeletion(@PathVariable Long id, @RequestParam String path) {
        return operations.recyclePreview(id, path);
    }

    /** To the Windows Recycle Bin, where the operator restores it: nothing is destroyed. */
    @DeleteMapping("/api/projects/{id}/browse")
    public ResponseEntity<Void> recycleEntry(@PathVariable Long id, @RequestParam String path) {
        operations.recycle(id, path);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/projects/{id}/browse/reveal")
    public ResponseEntity<Void> revealEntry(@PathVariable Long id, @Valid @RequestBody ProjectFileReveal body) {
        operations.reveal(id, body.path());
        return ResponseEntity.noContent().build();
    }
}
