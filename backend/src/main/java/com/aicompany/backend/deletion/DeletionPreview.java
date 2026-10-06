package com.aicompany.backend.deletion;

import java.util.ArrayList;
import java.util.List;

/**
 * What a delete would do, said the same way for every kind of thing (PHASE 30,
 * ADR-035 §3): what is removed, what is detached and stays, what is kept, which
 * files leave the disk -- and, when the delete cannot happen, why.
 *
 * <p>The console shows these lines before the operator confirms; the server
 * computes them with the same rules it applies when deleting, so the preview and
 * the act cannot disagree. {@code allowed} is false exactly when there are
 * blockers, and a delete attempted anyway is a {@code 409 deletion-blocked}
 * carrying the same sentences.
 */
public record DeletionPreview(String kind, List<Subject> subjects, List<String> deletes, List<String> detaches,
                              List<String> keeps, List<String> files, List<String> blockers, boolean allowed) {

    /** One thing that would be deleted: its identifier (a number, a key or a path) and its name. */
    public record Subject(String id, String name) {
    }

    public DeletionPreview {
        subjects = List.copyOf(subjects);
        deletes = List.copyOf(deletes);
        detaches = List.copyOf(detaches);
        keeps = List.copyOf(keeps);
        files = List.copyOf(files);
        blockers = List.copyOf(blockers);
    }

    public static Builder of(String kind) {
        return new Builder(kind);
    }

    /** Refuses with the preview's own sentences when it has blockers. */
    public DeletionPreview requireAllowed() {
        if (!blockers.isEmpty()) {
            throw new DeletionBlockedException(blockers);
        }
        return this;
    }

    public static final class Builder {
        private final String kind;
        private final List<Subject> subjects = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();
        private final List<String> detaches = new ArrayList<>();
        private final List<String> keeps = new ArrayList<>();
        private final List<String> files = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();

        private Builder(String kind) {
            this.kind = kind;
        }

        public Builder subject(Object id, String name) {
            subjects.add(new Subject(String.valueOf(id), name));
            return this;
        }

        public Builder deletes(String line) {
            deletes.add(line);
            return this;
        }

        public Builder detaches(String line) {
            detaches.add(line);
            return this;
        }

        public Builder keeps(String line) {
            keeps.add(line);
            return this;
        }

        public Builder file(String path) {
            files.add(path);
            return this;
        }

        public Builder blocker(String line) {
            blockers.add(line);
            return this;
        }

        public DeletionPreview build() {
            return new DeletionPreview(kind, subjects, deletes, detaches, keeps, files, blockers, blockers.isEmpty());
        }
    }
}
