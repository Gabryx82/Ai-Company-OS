package com.aicompany.backend.hitl.lesson;

import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.hitl.LessonFormat;
import com.aicompany.backend.hitl.lesson.Lesson.Exercise;
import com.aicompany.backend.hitl.lesson.Lesson.Kind;
import com.aicompany.backend.hitl.lesson.Lesson.Step;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PHASE 37 (ADR-044): learning that is done, not read. A lesson is a file an agent
 * wrote in the project ({@code lessons/<TASK>.lezione.md}); this service brings it to
 * the operator one act at a time and keeps what they did with it:
 *
 * <ul>
 *   <li>a <b>prediction</b>, answered before the code is shown;</li>
 *   <li>each <b>step</b>, whose code the operator copies into the file the lesson names
 *       -- and which is done when that file, in the project's folder, holds it;</li>
 *   <li>the <b>exercises</b>: a quiz, a blank to fill, an error to find, an explanation
 *       in the operator's own words;</li>
 *   <li>the <b>concepts</b> a finished lesson leaves, which come back as cards to review
 *       after 1, 3, 7, 16 and 35 days.</li>
 * </ul>
 *
 * <p>An answer is checked here, against the file: the view the console is given holds
 * the solution of an item back until the operator has given theirs. The lesson file
 * is only ever read; the project's code is only ever read. What is written is the
 * progress, in the database.
 */
@Service
public class LessonService {

    static final Pattern LESSON_PATH = Pattern.compile("^lessons/[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.lezione\\.md$");
    static final int MAX_LESSONS = 200;
    static final int MAX_LESSON_BYTES = 512 * 1024;
    static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    /** Days until a card comes back, by box: a card that was known moves up one. */
    static final int[] INTERVALS = {0, 1, 3, 7, 16, 35};

    private static final String PREDICTION = "PREDICTION";
    private static final String STEP = "STEP";
    private static final String EXERCISE = "EXERCISE";

    // --- what the console is given ------------------------------------------------------

    /** @param task the label of the task the lesson belongs to ({@code TASK-012}), from its file name */
    public record LessonSummary(String path, String title, @Nullable String task, int items, int done, boolean completed,
                                List<String> problems, Instant modified) {
    }

    /** {@code answer}: what the lesson expected, once the operator has given theirs. */
    public record PredictionView(String question, boolean answered, @Nullable String yourAnswer, @Nullable String answer) {
    }

    public record StepView(int index, String title, @Nullable String file, String mode, @Nullable String language,
                           String code, List<Lesson.Line> lines, @Nullable String why, boolean done) {
    }

    /**
     * {@code solution}, {@code explanation} and {@code keyPoints} are null and empty until
     * the exercise is done. For a quiz the solution is the index of the right option.
     */
    public record ExerciseView(int index, String kind, String prompt, List<String> options, @Nullable String language,
                               @Nullable String code, int attempts, boolean done, @Nullable Boolean firstTryRight,
                               @Nullable String yourAnswer, @Nullable String solution, @Nullable String explanation,
                               List<String> keyPoints) {
    }

    /** {@code concepts}: what the lesson leaves to remember, shown when it is completed. */
    public record LessonView(String path, String title, @Nullable String task, String intro, @Nullable PredictionView prediction,
                             List<StepView> steps, List<ExerciseView> exercises, List<Lesson.Concept> concepts,
                             List<String> problems, int items, int done, boolean completed) {
    }

    /** {@code status}: WRITTEN, MARKED, FILE_MISSING, DIFFERENT, NO_FILE, PATH_REFUSED. */
    public record StepCheck(int index, String status, String detail, boolean done) {
    }

    /** {@code correct} is null for an explanation in the operator's own words: it is compared, not judged. */
    public record ExerciseResult(int index, @Nullable Boolean correct, boolean done, int attempts, @Nullable String solution,
                                 @Nullable String explanation, List<String> keyPoints) {
    }

    /** {@code explanation} is given once the card is turned: asking for the cards due does not show it. */
    public record ReviewCard(Long id, Long projectId, String projectName, String lessonPath, String name, String explanation,
                       int box, LocalDate dueOn, int reviews) {
    }

    public record ReviewDeck(List<ReviewCard> due, int total, @Nullable LocalDate nextDue) {
    }

    public record ProjectLessons(Long projectId, String projectName, List<LessonSummary> lessons) {
    }

    public record LessonStats(int lessons, int lessonsCompleted, int stepsWritten, int exercisesAnswered, int exercisesRightFirstTry,
                        int predictions) {
    }

    /** {@code streakDays}: days in a row, up to today or yesterday, on which something was learnt. */
    public record LearningOverview(List<ProjectLessons> projects, LessonStats stats, int cardsDue, int cards, int streakDays) {
    }

    private record Progress(int attempts, @Nullable Boolean firstTryRight, boolean done, @Nullable String answer) {
        static final Progress NONE = new Progress(0, null, false, null);
    }

    private record Loaded(Path file, String path, Lesson lesson, Instant modified) {
    }

    private final ProjectRepository projects;
    private final ProjectWorkspaceService workspace;
    private final JdbcTemplate jdbc;

    public LessonService(ProjectRepository projects, ProjectWorkspaceService workspace, JdbcTemplate jdbc) {
        this.projects = projects;
        this.workspace = workspace;
        this.jdbc = jdbc;
    }

    // --- reading ------------------------------------------------------------------------

    public List<LessonSummary> list(Long projectId) {
        requireProject(projectId);
        Map<String, Map<String, Progress>> progress = progressOf(projectId);
        return lessons(projectId).stream().map(l -> summary(l, progress.getOrDefault(l.path(), Map.of()))).toList();
    }

    public LessonView view(Long projectId, String path) {
        Loaded loaded = load(projectId, path);
        return view(loaded, progressOf(projectId).getOrDefault(loaded.path(), Map.of()));
    }

    /** Every project's lessons, the cards to review today and how the learning is going. */
    public LearningOverview overview() {
        List<ProjectLessons> out = new ArrayList<>();
        int lessons = 0;
        int completed = 0;
        for (Project project : projects.findAll().stream().sorted(Comparator.comparing(Project::getName)).toList()) {
            List<Loaded> loaded;
            try {
                loaded = lessons(project.getId());
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (loaded.isEmpty()) {
                continue;
            }
            Map<String, Map<String, Progress>> progress = progressOf(project.getId());
            List<LessonSummary> summaries = loaded.stream().map(l -> summary(l, progress.getOrDefault(l.path(), Map.of()))).toList();
            lessons += summaries.size();
            completed += (int) summaries.stream().filter(LessonSummary::completed).count();
            out.add(new ProjectLessons(project.getId(), project.getName(), summaries));
        }
        LessonStats stats = new LessonStats(lessons, completed,
                count("SELECT count(*) FROM lesson_progress WHERE item_kind = 'STEP' AND done"),
                count("SELECT count(*) FROM lesson_progress WHERE item_kind = 'EXERCISE' AND done"),
                count("SELECT count(*) FROM lesson_progress WHERE item_kind = 'EXERCISE' AND first_try_right"),
                count("SELECT count(*) FROM lesson_progress WHERE item_kind = 'PREDICTION' AND done"));
        return new LearningOverview(out, stats,
                count("SELECT count(*) FROM learning_concepts WHERE due_on <= CURRENT_DATE"),
                count("SELECT count(*) FROM learning_concepts"), streak());
    }

    /** The lesson figures of one project, for its learning metrics. */
    public LessonStats statsOf(Long projectId) {
        List<LessonSummary> lessons;
        try {
            lessons = list(projectId);
        } catch (RuntimeException unreadable) {
            lessons = List.of();
        }
        return new LessonStats(lessons.size(), (int) lessons.stream().filter(LessonSummary::completed).count(),
                count("SELECT count(*) FROM lesson_progress WHERE project_id = ? AND item_kind = 'STEP' AND done", projectId),
                count("SELECT count(*) FROM lesson_progress WHERE project_id = ? AND item_kind = 'EXERCISE' AND done", projectId),
                count("SELECT count(*) FROM lesson_progress WHERE project_id = ? AND item_kind = 'EXERCISE' AND first_try_right", projectId),
                count("SELECT count(*) FROM lesson_progress WHERE project_id = ? AND item_kind = 'PREDICTION' AND done", projectId));
    }

    // --- doing --------------------------------------------------------------------------

    /** The operator's prediction, given before the code: recorded as it is, and answered with what the lesson expected. */
    public PredictionView predict(Long projectId, String path, String answer, String by) {
        Loaded loaded = load(projectId, path);
        Lesson.Prediction prediction = loaded.lesson().prediction();
        if (prediction == null) {
            throw LessonNotFoundException.item("This lesson has no prediction");
        }
        String given = required(answer);
        record(projectId, loaded.path(), PREDICTION, 0, null, true, given, by);
        finishIfComplete(projectId, loaded);
        return new PredictionView(prediction.question(), true, given, prediction.answer());
    }

    /**
     * Whether the operator wrote the step: the file the lesson names, in the project's
     * folder, holds every line of the step's code, in order. {@code manual} marks a step
     * done on the operator's word -- for one without a file, or whose code they adapted.
     */
    public StepCheck checkStep(Long projectId, String path, int index, boolean manual, String by) {
        Loaded loaded = load(projectId, path);
        Step step = item(loaded.lesson().steps(), index, "step");
        StepCheck check = manual ? new StepCheck(index, "MARKED", "Segnato come fatto.", true) : verify(projectId, step, index);
        if (check.done()) {
            record(projectId, loaded.path(), STEP, index, null, true, check.status(), by);
            finishIfComplete(projectId, loaded);
        }
        return check;
    }

    /**
     * An answer to an exercise. {@code reveal} gives the solution up: the exercise is done,
     * and not right at the first try.
     */
    public ExerciseResult answer(Long projectId, String path, int index, @Nullable String answer, boolean reveal, String by) {
        Loaded loaded = load(projectId, path);
        Exercise exercise = item(loaded.lesson().exercises(), index, "exercise");
        Progress before = progressOf(projectId).getOrDefault(loaded.path(), Map.of()).getOrDefault(EXERCISE + "/" + index, Progress.NONE);
        if (before.done()) {
            return new ExerciseResult(index, before.firstTryRight(), true, before.attempts(), solutionOf(exercise),
                    exercise.explanation(), exercise.keyPoints());
        }
        int attempts = before.attempts() + 1;
        if (reveal) {
            record(projectId, loaded.path(), EXERCISE, index, false, true, before.answer(), by);
            finishIfComplete(projectId, loaded);
            return new ExerciseResult(index, false, true, attempts, solutionOf(exercise), exercise.explanation(), exercise.keyPoints());
        }
        String given = required(answer);
        Boolean correct = exercise.kind() == Kind.EXPLAIN ? null : right(exercise, given);
        boolean done = correct == null || correct;
        Boolean firstTry = correct == null ? null : before.attempts() == 0 ? correct : Boolean.FALSE;
        record(projectId, loaded.path(), EXERCISE, index, firstTry, done, given, by);
        if (done) {
            finishIfComplete(projectId, loaded);
        }
        return new ExerciseResult(index, correct, done, attempts, done ? solutionOf(exercise) : null,
                done ? exercise.explanation() : null, done ? exercise.keyPoints() : List.of());
    }

    // --- review -------------------------------------------------------------------------

    /** The cards due today, oldest first. Their explanation is on the back: {@link #reviewed} gives it. */
    public ReviewDeck review() {
        List<ReviewCard> due = jdbc.query("""
                SELECT c.id, c.project_id, p.name AS project_name, c.lesson_path, c.name, c.box, c.due_on, c.reviews
                FROM learning_concepts c JOIN projects p ON p.id = c.project_id
                WHERE c.due_on <= CURRENT_DATE ORDER BY c.due_on, c.id LIMIT 50""",
                (row, n) -> new ReviewCard(row.getLong("id"), row.getLong("project_id"), row.getString("project_name"),
                        row.getString("lesson_path"), row.getString("name"), "", row.getInt("box"),
                        row.getDate("due_on").toLocalDate(), row.getInt("reviews")));
        Date next = jdbc.queryForObject("SELECT min(due_on) FROM learning_concepts WHERE due_on > CURRENT_DATE", Date.class);
        return new ReviewDeck(due, count("SELECT count(*) FROM learning_concepts"), next == null ? null : next.toLocalDate());
    }

    /** The operator turned the card and says whether they knew it: it comes back later, or tomorrow. */
    public ReviewCard reviewed(Long cardId, boolean known) {
        List<Integer> boxes = jdbc.queryForList("SELECT box FROM learning_concepts WHERE id = ?", Integer.class, cardId);
        if (boxes.isEmpty()) {
            throw LessonNotFoundException.item("No card " + cardId);
        }
        int box = known ? Math.min(INTERVALS.length - 1, boxes.getFirst() + 1) : 1;
        jdbc.update("UPDATE learning_concepts SET box = ?, due_on = CURRENT_DATE + ?, reviews = reviews + 1, last_known = ?, "
                + "reviewed_at = now() WHERE id = ?", box, INTERVALS[box], known, cardId);
        return jdbc.queryForObject("""
                SELECT c.id, c.project_id, p.name AS project_name, c.lesson_path, c.name, c.explanation, c.box, c.due_on, c.reviews
                FROM learning_concepts c JOIN projects p ON p.id = c.project_id WHERE c.id = ?""",
                (row, n) -> new ReviewCard(row.getLong("id"), row.getLong("project_id"), row.getString("project_name"),
                        row.getString("lesson_path"), row.getString("name"), row.getString("explanation"), row.getInt("box"),
                        row.getDate("due_on").toLocalDate(), row.getInt("reviews")), cardId);
    }

    /** The back of a card: what the lesson said of the concept. Reading it changes nothing. */
    public ReviewCard turn(Long cardId) {
        List<ReviewCard> found = jdbc.query("""
                SELECT c.id, c.project_id, p.name AS project_name, c.lesson_path, c.name, c.explanation, c.box, c.due_on, c.reviews
                FROM learning_concepts c JOIN projects p ON p.id = c.project_id WHERE c.id = ?""",
                (row, n) -> new ReviewCard(row.getLong("id"), row.getLong("project_id"), row.getString("project_name"),
                        row.getString("lesson_path"), row.getString("name"), row.getString("explanation"), row.getInt("box"),
                        row.getDate("due_on").toLocalDate(), row.getInt("reviews")), cardId);
        if (found.isEmpty()) {
            throw LessonNotFoundException.item("No card " + cardId);
        }
        return found.getFirst();
    }

    // --- checking -----------------------------------------------------------------------

    private StepCheck verify(Long projectId, Step step, int index) {
        if (step.file() == null || step.code().isBlank()) {
            return new StepCheck(index, "NO_FILE", "La lezione non dice in quale file va questo passo: segnalo tu quando l'hai fatto.", false);
        }
        Path folder = workspace.folderOf(projectId).orElse(null);
        if (folder == null) {
            return new StepCheck(index, "FILE_MISSING", "Il progetto non ha una cartella in cui cercare " + step.file() + ".", false);
        }
        Path file;
        try {
            file = WorkspacePaths.inside(folder, step.file());
        } catch (RuntimeException refused) {
            return new StepCheck(index, "PATH_REFUSED", "«" + step.file() + "» non è un percorso dentro la cartella del progetto.", false);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new StepCheck(index, "FILE_MISSING", "Non trovo " + step.file() + " nella cartella del progetto: crealo e incolla il codice del passo.", false);
        }
        List<String> written;
        try {
            if (Files.size(file) > MAX_SOURCE_BYTES) {
                return new StepCheck(index, "DIFFERENT", step.file() + " è troppo grande per essere confrontato.", false);
            }
            written = Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(LessonService::squeeze).toList();
        } catch (IOException | RuntimeException unreadable) {
            return new StepCheck(index, "DIFFERENT", step.file() + " non si legge come testo.", false);
        }
        // Every line of the step, in order; other lines may stand between them -- the file may hold more than the step.
        int at = 0;
        for (String line : step.code().split("\n")) {
            String wanted = squeeze(line);
            if (wanted.isEmpty()) {
                continue;
            }
            int found = -1;
            for (int i = at; i < written.size(); i++) {
                if (written.get(i).equals(wanted)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return new StepCheck(index, "DIFFERENT", "In " + step.file() + " manca, o è scritta diversamente, questa riga: "
                        + (line.strip().length() > 160 ? line.strip().substring(0, 160) + "…" : line.strip()), false);
            }
            at = found + 1;
        }
        return new StepCheck(index, "WRITTEN", "Trovato in " + step.file() + ": il codice del passo è nel file.", true);
    }

    /** A line as it is compared: without the indentation, with one space wherever there are several. */
    static String squeeze(String line) {
        return line.strip().replaceAll("\\s+", " ");
    }

    static boolean right(Exercise exercise, String given) {
        return switch (exercise.kind()) {
            case QUIZ -> {
                try {
                    int chosen = Integer.parseInt(given.strip());
                    yield chosen >= 0 && chosen < exercise.options().size() && exercise.options().get(chosen).correct();
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case FILL -> {
                String answer = squeeze(given.replaceAll("^`+|`+$", ""));
                yield Stream.of(exercise.solution().split("\\|")).map(LessonService::squeeze).anyMatch(answer::equals);
            }
            case BUG -> {
                java.util.regex.Matcher number = Pattern.compile("(\\d{1,5})").matcher(given);
                yield number.find() && number.group(1).equals(exercise.solution());
            }
            case EXPLAIN -> true;
        };
    }

    /** The solution as the console shows it: for a quiz the index of the right option. */
    private static @Nullable String solutionOf(Exercise exercise) {
        if (exercise.kind() == Kind.QUIZ) {
            for (int i = 0; i < exercise.options().size(); i++) {
                if (exercise.options().get(i).correct()) {
                    return String.valueOf(i);
                }
            }
        }
        return exercise.solution();
    }

    // --- the files ----------------------------------------------------------------------

    private List<Loaded> lessons(Long projectId) {
        Path folder = workspace.folderOf(projectId).orElse(null);
        if (folder == null) {
            return List.of();
        }
        Path root = folder.resolve(LessonFormat.FOLDER);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Loaded> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(p -> LessonFormat.isLesson(p.getFileName().toString()))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().limit(MAX_LESSONS).toList()) {
                String path = LessonFormat.FOLDER + "/" + file.getFileName();
                if (LESSON_PATH.matcher(path).matches()) {
                    read(file, path).ifPresent(out::add);
                }
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return out;
    }

    private Loaded load(Long projectId, String path) {
        requireProject(projectId);
        if (path == null || !LESSON_PATH.matcher(path).matches()) {
            throw LessonNotFoundException.lesson(path);
        }
        Path folder = workspace.folderOf(projectId).orElseThrow(() -> LessonNotFoundException.lesson(path));
        Path file = WorkspacePaths.inside(folder, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw LessonNotFoundException.lesson(path);
        }
        return read(file, path).orElseThrow(() -> LessonNotFoundException.lesson(path));
    }

    private static Optional<Loaded> read(Path file, String path) {
        try {
            if (Files.size(file) > MAX_LESSON_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new Loaded(file, path, LessonParser.parse(Files.readString(file, StandardCharsets.UTF_8)),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    // --- the views ----------------------------------------------------------------------

    private static LessonSummary summary(Loaded loaded, Map<String, Progress> progress) {
        int done = done(loaded.lesson(), progress);
        return new LessonSummary(loaded.path(), loaded.lesson().title(), taskOf(loaded.path()), loaded.lesson().items(), done,
                loaded.lesson().items() > 0 && done == loaded.lesson().items(), loaded.lesson().problems(), loaded.modified());
    }

    private static LessonView view(Loaded loaded, Map<String, Progress> progress) {
        Lesson lesson = loaded.lesson();
        PredictionView prediction = null;
        if (lesson.prediction() != null) {
            Progress p = progress.getOrDefault(PREDICTION + "/0", Progress.NONE);
            prediction = new PredictionView(lesson.prediction().question(), p.done(), p.answer(),
                    p.done() ? lesson.prediction().answer() : null);
        }
        List<StepView> steps = new ArrayList<>();
        for (int i = 0; i < lesson.steps().size(); i++) {
            Step s = lesson.steps().get(i);
            steps.add(new StepView(i, s.title(), s.file(), s.mode().name(), s.language(), s.code(), s.lines(), s.why(),
                    progress.getOrDefault(STEP + "/" + i, Progress.NONE).done()));
        }
        List<ExerciseView> exercises = new ArrayList<>();
        for (int i = 0; i < lesson.exercises().size(); i++) {
            Exercise e = lesson.exercises().get(i);
            Progress p = progress.getOrDefault(EXERCISE + "/" + i, Progress.NONE);
            exercises.add(new ExerciseView(i, e.kind().name(), e.prompt(), e.options().stream().map(Lesson.Option::text).toList(),
                    e.language(), e.code(), p.attempts(), p.done(), p.firstTryRight(), p.answer(),
                    p.done() ? solutionOf(e) : null, p.done() ? e.explanation() : null, p.done() ? e.keyPoints() : List.of()));
        }
        int done = done(lesson, progress);
        boolean completed = lesson.items() > 0 && done == lesson.items();
        return new LessonView(loaded.path(), lesson.title(), taskOf(loaded.path()), lesson.intro(), prediction, steps, exercises,
                completed ? lesson.concepts() : List.of(), lesson.problems(), lesson.items(), done, completed);
    }

    /** How many items of the lesson as it is now are done: progress on an item the file no longer has does not count. */
    private static int done(Lesson lesson, Map<String, Progress> progress) {
        int done = lesson.prediction() != null && progress.getOrDefault(PREDICTION + "/0", Progress.NONE).done() ? 1 : 0;
        for (int i = 0; i < lesson.steps().size(); i++) {
            done += progress.getOrDefault(STEP + "/" + i, Progress.NONE).done() ? 1 : 0;
        }
        for (int i = 0; i < lesson.exercises().size(); i++) {
            done += progress.getOrDefault(EXERCISE + "/" + i, Progress.NONE).done() ? 1 : 0;
        }
        return done;
    }

    private static @Nullable String taskOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".lezione.md".length());
        return name.matches("^TASK-\\d+.*") ? name : null;
    }

    // --- progress -----------------------------------------------------------------------

    /** lesson path to item ({@code KIND/index}) to progress. */
    private Map<String, Map<String, Progress>> progressOf(Long projectId) {
        Map<String, Map<String, Progress>> out = new HashMap<>();
        jdbc.query("SELECT lesson_path, item_kind, item_index, attempts, first_try_right, done, answer FROM lesson_progress "
                + "WHERE project_id = ?", (java.sql.ResultSet row) -> {
            out.computeIfAbsent(row.getString("lesson_path"), k -> new HashMap<>())
                    .put(row.getString("item_kind") + "/" + row.getInt("item_index"),
                            new Progress(row.getInt("attempts"), (Boolean) row.getObject("first_try_right"),
                                    row.getBoolean("done"), row.getString("answer")));
        }, projectId);
        return out;
    }

    /** One attempt at an item. {@code firstTryRight} is kept as first written; done never goes back to not done. */
    private void record(Long projectId, String path, String kind, int index, @Nullable Boolean firstTryRight, boolean done,
                        @Nullable String answer, String by) {
        String kept = answer == null || answer.length() <= 2000 ? answer : answer.substring(0, 2000);
        jdbc.update("""
                INSERT INTO lesson_progress (project_id, lesson_path, item_kind, item_index, attempts, first_try_right, done,
                                             answer, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, now(), ?)
                ON CONFLICT (project_id, lesson_path, item_kind, item_index) DO UPDATE SET
                    attempts = lesson_progress.attempts + 1,
                    first_try_right = COALESCE(lesson_progress.first_try_right, EXCLUDED.first_try_right),
                    done = lesson_progress.done OR EXCLUDED.done,
                    answer = COALESCE(EXCLUDED.answer, lesson_progress.answer),
                    updated_at = now(), updated_by = EXCLUDED.updated_by""",
                projectId, path, kind, index, firstTryRight, done, kept, by);
    }

    /** A lesson whose every item is done leaves its concepts as cards, due tomorrow -- once. */
    private void finishIfComplete(Long projectId, Loaded loaded) {
        Lesson lesson = loaded.lesson();
        if (lesson.items() == 0 || done(lesson, progressOf(projectId).getOrDefault(loaded.path(), Map.of())) < lesson.items()) {
            return;
        }
        for (Lesson.Concept concept : lesson.concepts()) {
            jdbc.update("""
                    INSERT INTO learning_concepts (project_id, lesson_path, name, explanation, box, due_on)
                    VALUES (?, ?, ?, ?, 1, CURRENT_DATE + 1)
                    ON CONFLICT (project_id, lesson_path, name) DO NOTHING""",
                    projectId, loaded.path(), cut(concept.name(), 200), cut(concept.text(), 2000));
        }
    }

    /** Days in a row with learning on them, counted back from today -- or from yesterday, while today is still open. */
    private int streak() {
        Set<LocalDate> days = new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT (updated_at AT TIME ZONE current_setting('TimeZone'))::date FROM lesson_progress
                UNION SELECT DISTINCT (reviewed_at AT TIME ZONE current_setting('TimeZone'))::date FROM learning_concepts
                WHERE reviewed_at IS NOT NULL""", Date.class).stream().map(Date::toLocalDate).toList());
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE", Date.class).toLocalDate();
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (days.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    // --- helpers ------------------------------------------------------------------------

    private void requireProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    private static <T> T item(List<T> items, int index, String what) {
        if (index < 0 || index >= items.size()) {
            throw LessonNotFoundException.item("This lesson has no " + what + " " + index);
        }
        return items.get(index);
    }

    private static String required(@Nullable String answer) {
        if (answer == null || answer.isBlank()) {
            throw new RequestValidationException("answer", "must not be blank");
        }
        return answer.strip();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
