package com.aicompany.backend.hitl;

import com.aicompany.backend.graph.code.CodeGraphScanner;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the operator's work on a project says about the level they could work at
 * (PHASE 32, TD-45; ADR-022 §5). Measured, never decided: the suggestion is shown
 * with its reasons and the operator changes the level on the project's profile,
 * as ADR-022 §4 wants -- the system does not decide on its own that the operator
 * "has learnt".
 *
 * <p>The data: the verdict of the <em>first</em> review each task got at the
 * project's current level (V32 records the level of every review), the tasks the
 * operator completed by hand (the history of TD-38), and the pieces agents left
 * to the operator in the code -- {@code TODO(operatore)} markers, the GUIDED
 * contract of {@link AutonomyPolicy} -- still open in the project's folder.
 */
@Service
public class LearningService {

    /** First-pass acceptance needed to suggest the next level. */
    static final double RATE_TO_STEP_UP = 0.8;
    /** Out of the last {@code STEP_DOWN_WINDOW} tasks reviewed, this many sent back suggest the previous level. */
    static final int STEP_DOWN_WINDOW = 4;
    static final int STEP_DOWN_SENT_BACK = 3;
    static final String MARKER = "TODO(operatore)";
    static final int PIECES_LISTED = 20;
    static final Set<String> CODE = Set.of("java", "kt", "kts", "scala", "groovy", "ts", "tsx", "js", "jsx", "mjs",
            "cjs", "py", "go", "rs", "cs", "rb", "php", "c", "cc", "cpp", "h", "hpp", "swift", "dart", "sql", "html",
            "css", "scss", "vue", "svelte");

    public enum Direction { UP, DOWN, STAY }

    public record LevelSuggestion(Direction direction, AutonomyLevel level, List<String> reasons) {
    }

    public record Piece(String path, int line, String text) {
    }

    /**
     * {@code piecesOpen} is null when the project has no folder to look in: unknown,
     * not zero.
     */
    public record Learning(AutonomyLevel level, int reviewedTasks, int firstPassAccepted, int changesRequested,
                           int operatorCompletions, Integer piecesOpen, List<Piece> pieces, boolean piecesTruncated,
                           LevelSuggestion suggestion) {
    }

    private final ProjectRepository projects;
    private final ProjectWorkspaceService workspace;
    private final JdbcTemplate jdbc;

    public LearningService(ProjectRepository projects, ProjectWorkspaceService workspace, JdbcTemplate jdbc) {
        this.projects = projects;
        this.workspace = workspace;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Learning of(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        AutonomyLevel level = project.getAutonomyLevel();

        // The first review each task got at this level, in the order the tasks were first reviewed.
        List<Boolean> firstPass = jdbc.queryForList("""
                SELECT r.verdict = 'ACCEPTED' FROM task_reviews r JOIN tasks t ON t.id = r.task_id
                WHERE t.project_id = ? AND r.autonomy_level = ?
                  AND r.id = (SELECT min(f.id) FROM task_reviews f WHERE f.task_id = r.task_id AND f.autonomy_level = ?)
                ORDER BY r.id""", Boolean.class, projectId, level.name(), level.name());
        int changesRequested = count("""
                SELECT count(*) FROM task_reviews r JOIN tasks t ON t.id = r.task_id
                WHERE t.project_id = ? AND r.autonomy_level = ? AND r.verdict = 'CHANGES_REQUESTED'""",
                projectId, level.name());
        int byHand = count("""
                SELECT count(*) FROM task_transitions h JOIN tasks t ON t.id = h.task_id
                WHERE t.project_id = ? AND h.transition = 'COMPLETE' AND h.via = 'API'""", projectId);

        Optional<Path> folder = workspace.folderOf(projectId);
        Scan scan = folder.map(LearningService::scan).orElse(null);
        Integer open = scan == null ? null : scan.count;
        int accepted = (int) firstPass.stream().filter(Boolean::booleanValue).count();
        return new Learning(level, firstPass.size(), accepted, changesRequested, byHand, open,
                scan == null ? List.of() : scan.pieces, scan != null && scan.truncated, suggest(level, firstPass, open));
    }

    /**
     * The rule, in one place (ADR-022, revision of PHASE 32). {@code firstPass}: for
     * every task reviewed at this level, in order, whether its first review accepted it.
     */
    static LevelSuggestion suggest(AutonomyLevel level, List<Boolean> firstPass, Integer piecesOpen) {
        int reviewed = firstPass.size();
        if (level != AutonomyLevel.GUIDED && reviewed >= STEP_DOWN_WINDOW) {
            long sentBack = firstPass.subList(reviewed - STEP_DOWN_WINDOW, reviewed).stream().filter(ok -> !ok).count();
            if (sentBack >= STEP_DOWN_SENT_BACK) {
                return new LevelSuggestion(Direction.DOWN, previous(level), List.of("Nelle ultime " + STEP_DOWN_WINDOW
                        + " task riviste a questo livello, " + sentBack + " sono state rimandate alla prima review: "
                        + "conviene seguire il lavoro più da vicino."));
            }
        }
        if (level == AutonomyLevel.FINAL_REVIEW) {
            return new LevelSuggestion(Direction.STAY, level, List.of("È il livello più alto."));
        }
        int needed = needed(level);
        if (reviewed < needed) {
            return new LevelSuggestion(Direction.STAY, level, List.of("Per proporre il livello successivo servono "
                    + needed + " task riviste a questo livello: finora " + reviewed + "."));
        }
        long accepted = firstPass.subList(reviewed - needed, reviewed).stream().filter(Boolean::booleanValue).count();
        String rate = accepted + " delle ultime " + needed + " task accettate alla prima review ("
                + Math.round(100.0 * accepted / needed) + "%)";
        if (accepted < Math.ceil(RATE_TO_STEP_UP * needed)) {
            return new LevelSuggestion(Direction.STAY, level, List.of(rate + ": per salire serve almeno l'"
                    + Math.round(RATE_TO_STEP_UP * 100) + "%."));
        }
        List<String> reasons = new ArrayList<>(List.of(rate + "."));
        if (level == AutonomyLevel.GUIDED) {
            if (piecesOpen != null && piecesOpen > 0) {
                return new LevelSuggestion(Direction.STAY, level, List.of(rate + ".", "Restano " + piecesOpen
                        + " pezzi " + MARKER + " da scrivere: prima quelli."));
            }
            reasons.add(piecesOpen == null
                    ? "Il progetto non ha una cartella: i pezzi " + MARKER + " non si possono contare."
                    : "Nessun pezzo " + MARKER + " aperto nel codice.");
        }
        return new LevelSuggestion(Direction.UP, next(level), reasons);
    }

    static int needed(AutonomyLevel level) {
        return switch (level) {
            case GUIDED -> 5;
            case SUPERVISED -> 8;
            case DELEGATED -> 10;
            case FINAL_REVIEW -> 0;
        };
    }

    private static AutonomyLevel next(AutonomyLevel level) {
        return AutonomyLevel.values()[Math.min(level.ordinal() + 1, AutonomyLevel.values().length - 1)];
    }

    private static AutonomyLevel previous(AutonomyLevel level) {
        return AutonomyLevel.values()[Math.max(level.ordinal() - 1, 0)];
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // --- the pieces left to the operator ---------------------------------------------

    private record Scan(int count, List<Piece> pieces, boolean truncated) {
    }

    /**
     * The open {@code TODO(operatore)} markers in the project's code. Only source
     * files: the rules that ask for the marker (AGENTS.md, the handoffs) are Markdown
     * and mention it without being a piece. Bounded like the code graph.
     */
    static Scan scan(Path root) {
        List<Piece> pieces = new ArrayList<>();
        int[] count = {0};
        int[] files = {0};
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && (CodeGraphScanner.SKIPPED.contains(dir.getFileName().toString())
                            || attrs.isSymbolicLink())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || attrs.size() > CodeGraphScanner.MAX_BYTES || !isCode(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++files[0] > CodeGraphScanner.MAX_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(MARKER)) {
                                count[0]++;
                                if (pieces.size() < PIECES_LISTED) {
                                    String text = lines.get(i).strip();
                                    pieces.add(new Piece(root.relativize(file).toString().replace('\\', '/'), i + 1,
                                            text.length() > 160 ? text.substring(0, 160) : text));
                                }
                            }
                        }
                    } catch (IOException | RuntimeException unreadable) {
                        // not UTF-8, or locked: not a piece anyone can read
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            return new Scan(count[0], pieces, true);
        }
        return new Scan(count[0], pieces, truncated[0]);
    }

    private static boolean isCode(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && CODE.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
