package com.aicompany.backend.deletion;

import com.aicompany.backend.api.Precondition;
import org.springframework.http.HttpHeaders;
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
    public DeletionService.Impact previewTask(@PathVariable Long id) {
        return deletions.previewTask(id);
    }

    @DeleteMapping("/api/tasks/{id}")
    public DeletionService.Impact deleteTask(@PathVariable Long id,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             @RequestParam(required = false) String confirm, Authentication caller) {
        return deletions.deleteTask(id, confirm, caller.getName(), Precondition.fromHeader(ifMatch));
    }

    @GetMapping("/api/projects/{id}/deletion")
    public DeletionService.Impact previewProject(@PathVariable Long id,
                                                 @RequestParam DeletionService.TasksPolicy tasks) {
        return deletions.previewProject(id, tasks);
    }

    /** {@code tasks} has no default on purpose: what happens to the tasks is always said out loud. */
    @DeleteMapping("/api/projects/{id}")
    public DeletionService.Impact deleteProject(@PathVariable Long id, @RequestParam DeletionService.TasksPolicy tasks,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @RequestParam(required = false) String confirm, Authentication caller) {
        return deletions.deleteProject(id, tasks, confirm, caller.getName(), Precondition.fromHeader(ifMatch));
    }
}
