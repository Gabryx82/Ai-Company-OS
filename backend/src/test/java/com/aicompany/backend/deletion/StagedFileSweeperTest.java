package com.aicompany.backend.deletion;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-70): the start sweeps the staged files an interrupted delete left in
 * the folders AI Company OS deletes in -- a project's references and documents, the
 * skill library -- and nothing in the rest of the workspace, which is the operator's.
 */
class StagedFileSweeperTest extends AbstractPostgresTest {

    @Autowired
    private StagedFileSweeper sweeper;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private SkillLibrary library;
    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path workspace;

    private static String staged(String name) {
        return "." + name + ManagedFiles.STAGED_MARK + UUID.randomUUID();
    }

    @Test
    void theManagedFoldersOfEveryProjectAndTheLibraryAreSweptAndNothingElse() throws Exception {
        Long id = projects.saveAndFlush(new Project("Sweep " + System.nanoTime(), null)).getId();
        jdbc.update("UPDATE projects SET workspace_path = ? WHERE id = ?", workspace.toString(), id);
        try {
            Path reference = Files.createDirectories(workspace.resolve("references/images")).resolve(staged("a.png"));
            Path document = Files.createDirectories(workspace.resolve("docs")).resolve(staged("notes.md"));
            Path handoff = Files.createDirectories(workspace.resolve(".aicos/handoffs")).resolve(staged("TASK-001-claude-code.md"));
            Path inTheCode = Files.createDirectories(workspace.resolve("src")).resolve(staged("Main.java"));
            Path inTheLibrary = Files.createDirectories(library.root().resolve("skills/swept")).resolve(staged("SKILL.md"));
            for (Path file : new Path[] {reference, document, handoff, inTheCode, inTheLibrary}) {
                Files.writeString(file, "left by a crash");
            }

            assertThat(sweeper.sweepAll()).contains(reference, document, handoff, inTheLibrary).doesNotContain(inTheCode);
            assertThat(reference).doesNotExist();
            assertThat(document).doesNotExist();
            assertThat(handoff).doesNotExist();
            assertThat(inTheLibrary).doesNotExist();
            assertThat(inTheCode).as("outside the managed folders: the operator's, never touched").exists();
        } finally {
            jdbc.update("DELETE FROM projects WHERE id = ?", id);
        }
    }
}
