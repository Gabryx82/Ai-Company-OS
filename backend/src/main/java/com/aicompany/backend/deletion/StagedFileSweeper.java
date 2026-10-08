package com.aicompany.backend.deletion;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 32 (TD-70): at every start, the hidden staged files a crash left between
 * a commit and their removal ({@link ManagedFiles}) are swept from the folders AI
 * Company OS deletes in -- the skill library, and in each project's workspace
 * {@code references/} and the document folders. A start is the right moment:
 * the crash that leaves them is followed by one. Nothing else is touched: only
 * names {@link ManagedFiles} gives, under the folders it manages.
 */
@Component
public class StagedFileSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(StagedFileSweeper.class);

    private final SkillLibrary library;
    private final ProjectRepository projects;

    public StagedFileSweeper(SkillLibrary library, ProjectRepository projects) {
        this.library = library;
        this.projects = projects;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        List<Path> swept = sweepAll();
        if (!swept.isEmpty()) {
            LOG.info("Swept {} staged files left by an interrupted delete: {}", swept.size(), swept);
        }
    }

    /** Every managed folder, swept; returns the files removed. */
    public List<Path> sweepAll() {
        List<Path> swept = new ArrayList<>(ManagedFiles.sweep(library.root()));
        for (Project project : projects.findAll()) {
            if (project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
                continue;
            }
            try {
                Path workspace = Path.of(project.getWorkspacePath());
                swept.addAll(ManagedFiles.sweep(workspace.resolve("references")));
                for (String folder : WorkspacePaths.DELETABLE_DOCUMENT_ROOTS) {
                    swept.addAll(ManagedFiles.sweep(workspace.resolve(folder)));
                }
            } catch (InvalidPathException unusable) {
                // a workspace path that is not a path on this machine has nothing to sweep
            }
        }
        return swept;
    }
}
