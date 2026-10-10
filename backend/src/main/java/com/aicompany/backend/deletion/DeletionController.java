package com.aicompany.backend.deletion;

import com.aicompany.backend.api.Precondition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deleting for real (ADR-027). The DELETE routes are admin-only in
 * SecurityConfiguration; the previews are readable by anybody who can read the
 * resource, so the console can show what a delete would take before an admin
 * decides.
 */
@RestController
public class DeletionController {

    private final DeletionService deletions;

    public DeletionController(DeletionService deletions) {
        this.deletions = deletions;
    }

    @GetMapping("/api/tasks/{id}/deletion")
    public DeletionPreview previewTask(@PathVariable Long id) {
        return deletions.previewTask(id);
    }

    /** PHASE 32 (TD-72): {@code 204}, like every other delete; what it took was the preview. */
    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @RequestParam(required = false) String confirm, Authentication caller) {
        deletions.deleteTask(id, confirm, caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** PHASE 30 (ADR-035 §8): what deleting the project's whole Blueprint would take. */
    @GetMapping("/api/projects/{id}/blueprint/deletion")
    public com.aicompany.backend.deletion.DeletionPreview previewBlueprint(@PathVariable Long id) {
        return deletions.previewBlueprint(id);
    }

    /** The whole Blueprint, never the code: admin-only, under the layout's tag, with the project's name typed back. */
    @DeleteMapping("/api/projects/{id}/blueprint")
    public org.springframework.http.ResponseEntity<Void> deleteBlueprint(@PathVariable Long id,
                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                @RequestParam(required = false) String confirm, Authentication caller) {
        deletions.deleteBlueprint(id, confirm, caller.getName(), Precondition.fromHeader(ifMatch));
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/api/projects/{id}/deletion")
    public DeletionPreview previewProject(@PathVariable Long id, @RequestParam DeletionService.TasksPolicy tasks) {
        return deletions.previewProject(id, tasks);
    }

    /** {@code tasks} has no default on purpose: what happens to the tasks is always said out loud. */
    @DeleteMapping("/api/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id, @RequestParam DeletionService.TasksPolicy tasks,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @RequestParam(required = false) String confirm, Authentication caller) {
        deletions.deleteProject(id, tasks, confirm, caller.getName(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
