package com.aicompany.backend.journal;

import com.aicompany.backend.api.RequestValidationException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PHASE 37 (ADR-047): what the agents are doing, for the 3D office -- each agent's journal,
 * and what the agent changed in the working folder since its task was handed off. Read only.
 */
@RestController
public class JournalController {

    private final AgentJournal journal;
    private final WorkChanges changes;

    public JournalController(AgentJournal journal, WorkChanges changes) {
        this.journal = journal;
        this.changes = changes;
    }

    /**
     * What the agents did, step by step, the most recent first: the moments the control plane
     * records and the steps each agent writes in its diary. One agent with {@code agentId}.
     */
    @GetMapping("/api/journal")
    public List<AgentJournal.JournalEntry> journal(@RequestParam(required = false) @Nullable Long agentId,
                                                   @RequestParam(defaultValue = "" + AgentJournal.DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > AgentJournal.MAX_LIMIT) {
            throw new RequestValidationException("limit", "must be between 1 and " + AgentJournal.MAX_LIMIT);
        }
        return journal.entries(agentId, limit);
    }

    /**
     * The files the agent added, modified or deleted in the working folder since the task's latest
     * handoff, with the lines added and removed; {@code tracked} false when there is nothing to compare
     * with (a task never handed off, or handed off before the copies existed).
     */
    @GetMapping("/api/tasks/{id}/changes")
    public WorkChanges.TaskChanges changes(@PathVariable Long id) {
        java.util.Optional<java.nio.file.Path> folder = journal.folderOf(id);
        return changes.changes(id, journal.baselineOf(id).orElse(null), folder.orElse(null));
    }

    /** One changed file, line by line, in hunks with three lines of context. */
    @GetMapping("/api/tasks/{id}/changes/file")
    public WorkChanges.FileDiff changedFile(@PathVariable Long id, @RequestParam String path) {
        java.util.Optional<java.nio.file.Path> folder = journal.folderOf(id);
        return changes.diff(id, journal.baselineOf(id).orElse(null), folder.orElse(null), path)
                .orElseThrow(() -> new ChangedFileNotFoundException(id, path));
    }
}
