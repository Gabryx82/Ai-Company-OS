package com.aicompany.backend.workspace;

import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** ADR-020 W2 and W3: every way out of the workspace, and every file the API does not write. */
class WorkspacePathsTest {

    @TempDir
    Path root;

    @Test
    void aRelativeDocumentPathResolvesInsideTheWorkspace() {
        assertThat(WorkspacePaths.writableDocument(root, "tasks/TASK-001.md"))
                .isEqualTo(root.resolve("tasks").resolve("TASK-001.md").toAbsolutePath().normalize());
        assertThat(WorkspacePaths.writableDocument(root, "MASTER_PROMPT.md"))
                .isEqualTo(root.resolve("MASTER_PROMPT.md").toAbsolutePath().normalize());
    }

    @Test
    void everyWayOutIsRefused() {
        for (String hostile : List.of("../outside.md", "docs/../../outside.md", "/etc/passwd", "C:/Windows/win.ini",
                "docs\\..\\..\\x.md", "tasks/NUL.md", "tasks/con", "docs/a.md:stream", "", "   ", ".")) {
            assertThatThrownBy(() -> WorkspacePaths.inside(root, hostile))
                    .as(hostile)
                    .isInstanceOf(WorkspacePathRefusedException.class);
        }
    }

    @Test
    void onlyTextDocumentsInTheGovernedPlacesAreWritable() {
        for (String refused : List.of("src/Main.java", "pom.xml", "docs/run.ps1", "tasks/x.exe", "README.md",
                "references/image.png")) {
            assertThatThrownBy(() -> WorkspacePaths.writableDocument(root, refused))
                    .as(refused)
                    .isInstanceOf(WorkspacePathRefusedException.class);
        }
        for (String allowed : List.of("docs/phases/PHASE_1.md", ".aicos/plan.json", "references/README.md",
                "AGENTS.md", "CLAUDE.md", "tasks/notes.txt")) {
            assertThat(WorkspacePaths.writableDocument(root, allowed)).as(allowed).isNotNull();
        }
    }

    @Test
    void aSymbolicLinkThatPointsOutsideIsRefused() throws IOException {
        Path outside = Files.createTempDirectory("aicos-outside");
        Path link = root.resolve("docs");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException noSymlinks) {
            assumeThat(false).as("symbolic links are not available here: " + noSymlinks).isTrue();
        }

        assertThatThrownBy(() -> WorkspacePaths.writableDocument(root, "docs/escape.md"))
                .isInstanceOf(WorkspacePathRefusedException.class)
                .hasMessageContaining("outside");
    }
}
