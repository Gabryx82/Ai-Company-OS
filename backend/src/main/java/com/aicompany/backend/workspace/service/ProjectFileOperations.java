package com.aicompany.backend.workspace.service;

import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.deletion.DeletionPreview;
import com.aicompany.backend.deletion.ManagedFiles;
import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.workspace.desktop.DesktopShell;
import com.aicompany.backend.workspace.exception.WorkspaceEntryExistsException;
import com.aicompany.backend.workspace.exception.WorkspaceEntryInUseException;
import com.aicompany.backend.workspace.exception.WorkspaceFileNotFoundException;
import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.exception.WorkspaceUnavailableException;
import com.aicompany.backend.workspace.service.ProjectFileBrowser.Entry;
import com.aicompany.backend.workspace.service.ProjectFileBrowser.EntryKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PHASE 33 (ADR-040): what the explorer's context menu does to a project's files --
 * rename, a new file or folder, the Recycle Bin, an Explorer window -- on the
 * operator's request, one item at a time.
 *
 * <p>The rules of reading still hold (ADR-039 F1: every path inside the folder, links
 * included), and writing adds its own:
 * <ul>
 *   <li><b>Nothing is destroyed.</b> Removing moves to the Windows Recycle Bin, where the
 *       operator restores it; where there is no bin, it is refused. Nothing overwrites a
 *       name that is already there.</li>
 *   <li><b>What AI Company OS stands on stays.</b> The structure of the workspace
 *       (ADR-035 §6) and the repository's {@code .git} are not renamed or removed; a
 *       document a task, a phase or a handoff points at is not either -- the row would
 *       point at nothing.</li>
 *   <li><b>A link is the operator's.</b> It is shown, and opened if it stays inside, but
 *       renaming or removing it -- and maybe what it points at -- is Explorer's job.</li>
 *   <li>An archived project is frozen: its files are read, never changed from here.</li>
 * </ul>
 * No row is written: the methods read the project and its records, and change the disk.
 */
@Service
public class ProjectFileOperations {

    /** Beyond these a folder is refused: the Recycle Bin may not hold it, and Windows would destroy it instead. */
    static final long MAX_RECYCLE_BYTES = 1L << 30;
    static final int MAX_RECYCLE_ENTRIES = 10_000;

    /** ADR-035 §6: edited, or recreated by «Prepara workspace» -- never renamed or removed from here. */
    private static final Set<String> STRUCTURE = Set.of("master_prompt.md", "agents.md", "claude.md", ".aicos",
            ".aicos/project.json", ".aicos/plan.json", ".aicos/context_map.md");

    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\u0000-\\u001f]");
    private static final Pattern DEVICE = Pattern.compile("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$",
            Pattern.CASE_INSENSITIVE);

    private record Use(String path, String sentence) {
    }

    private record Contents(int files, int folders, long bytes, boolean over) {
    }

    private final ProjectRepository projects;
    private final ProjectWorkspaceService workspaces;
    private final DesktopShell desktop;
    private final JdbcTemplate jdbc;

    public ProjectFileOperations(ProjectRepository projects, ProjectWorkspaceService workspaces, DesktopShell desktop,
                                 JdbcTemplate jdbc) {
        this.projects = projects;
        this.workspaces = workspaces;
        this.desktop = desktop;
        this.jdbc = jdbc;
    }

    // --- rename ---------------------------------------------------------------------

    /** A new name for a file or a folder, in the same folder. Answers the entry under its new name. */
    @Transactional(readOnly = true)
    public Entry rename(Long projectId, String relative, String newName) {
        Path root = writableRoot(projectId);
        Path source = existing(root, relative);
        String rel = WorkspacePaths.relativeText(root, source);
        refuseProtected(rel, "rinominato");
        if (isLink(source)) {
            throw new WorkspacePathRefusedException("'" + rel + "' è un collegamento: si rinomina da Esplora risorse");
        }
        String name = validName(newName);
        String oldName = source.getFileName().toString();
        if (name.equals(oldName)) {
            return ProjectFileBrowser.entry(root, source);
        }
        Path target = source.resolveSibling(name);
        WorkspacePaths.inside(root, WorkspacePaths.relativeText(root, target));
        List<Use> uses = usesOf(projectId, rel);
        if (!uses.isEmpty()) {
            throw new WorkspaceEntryInUseException("'" + rel + "' non si rinomina: " + uses.getFirst().sentence()
                    + " Il record punterebbe a un file che non c'è più.");
        }
        boolean sameFile = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && sameFile(source, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !sameFile) {
            throw new WorkspaceEntryExistsException("'" + WorkspacePaths.relativeText(root, target) + "' esiste già");
        }
        try {
            if (sameFile) {
                // Only the case changes ("readme.md" to "README.md"): Windows sees one name, so through a step.
                Path step = source.resolveSibling("." + oldName + ".aicos-renaming-" + UUID.randomUUID());
                Files.move(source, step);
                Files.move(step, target);
            } else {
                Files.move(source, target);
            }
        } catch (FileAlreadyExistsException raced) {
            throw new WorkspaceEntryExistsException("'" + WorkspacePaths.relativeText(root, target) + "' esiste già");
        } catch (IOException refused) {
            throw new WorkspaceEntryInUseException("Windows non ha rinominato '" + rel + "': forse è aperto in un altro "
                    + "programma (" + refused.getClass().getSimpleName() + ").");
        }
        return ProjectFileBrowser.entry(root, target);
    }

    // --- new file, new folder --------------------------------------------------------------

    /** An empty file or folder in {@code parent} (empty: the project folder). Never over an existing name. */
    @Transactional(readOnly = true)
    public Entry create(Long projectId, String parent, String name, EntryKind kind) {
        if (kind != EntryKind.FILE && kind != EntryKind.DIRECTORY) {
            throw new RequestValidationException("kind", "must be FILE or DIRECTORY");
        }
        Path root = writableRoot(projectId);
        Path folder = isRoot(parent) ? root : WorkspacePaths.inside(root, parent);
        if (!Files.isDirectory(folder)) {
            throw new WorkspaceFileNotFoundException("No folder '" + parent + "' in the project folder");
        }
        if (!folder.equals(root) && insideGit(WorkspacePaths.relativeText(root, folder))) {
            throw new WorkspacePathRefusedException("'" + parent + "' è la cartella di git: si cambia con git, non da qui");
        }
        String valid = validName(name);
        Path target = folder.resolve(valid);
        String rel = WorkspacePaths.relativeText(root, target);
        WorkspacePaths.inside(root, rel);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceEntryExistsException("'" + rel + "' esiste già");
        }
        try {
            if (kind == EntryKind.DIRECTORY) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
        } catch (FileAlreadyExistsException raced) {
            throw new WorkspaceEntryExistsException("'" + rel + "' esiste già");
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + rel + "' could not be created: " + e.getMessage());
        }
        return ProjectFileBrowser.entry(root, target);
    }

    // --- the Recycle Bin ------------------------------------------------------------------

    /** What moving a file or a folder to the Recycle Bin would do, and what stops it. */
    @Transactional(readOnly = true)
    public DeletionPreview recyclePreview(Long projectId, String relative) {
        Project project = project(projectId);
        Path root = existingRoot(projectId);
        Path target = existing(root, relative);
        String rel = WorkspacePaths.relativeText(root, target);
        boolean link = isLink(target);
        boolean folder = !link && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        DeletionPreview.Builder preview = DeletionPreview.of(folder ? "FOLDER" : "FILE")
                .subject(rel, target.getFileName().toString());

        Contents size = folder ? sizeOf(target) : null;
        if (link) {
            preview.deletes("Il collegamento «" + rel + "» andrebbe nel Cestino di Windows.");
        } else if (folder) {
            preview.deletes("La cartella «" + rel + "» va nel Cestino di Windows con tutto ciò che contiene"
                    + (size.over() ? "." : ": " + count(size.files(), "file", "file") + " e "
                    + count(size.folders(), "cartella", "cartelle") + ", " + bytes(size.bytes()) + "."));
        } else {
            preview.deletes("Il file «" + rel + "» va nel Cestino di Windows.");
        }
        preview.keeps("Si ripristina dal Cestino di Windows, finché non lo svuoti.");

        if (project.isArchived()) {
            preview.blocker("Il progetto è archiviato: i suoi file si leggono, non si cambiano da qui.");
        }
        if (isStructure(rel)) {
            preview.blocker("«" + rel + "» è la struttura del workspace: si modifica, o si ricrea con «Prepara workspace».");
        }
        if (insideGit(rel)) {
            preview.blocker("«" + rel + "» è il repository git: si cambia con git, non da qui.");
        }
        if (link) {
            preview.blocker("«" + rel + "» è un collegamento: si elimina da Esplora risorse, dove si vede anche a che cosa punta.");
        }
        for (Use use : usesOf(projectId, rel)) {
            preview.blocker(use.sentence() + (use.path().equalsIgnoreCase(rel) ? "" : " (" + use.path() + ")"));
        }
        if (size != null && size.over()) {
            preview.blocker("La cartella è troppo grande da spostare da qui (oltre " + MAX_RECYCLE_ENTRIES + " elementi o "
                    + bytes(MAX_RECYCLE_BYTES) + "): il Cestino potrebbe non contenerla e Windows la cancellerebbe per "
                    + "sempre. Eliminala da Esplora risorse.");
        }
        desktop.recycleBinRefusal(target).ifPresent(preview::blocker);
        return preview.build();
    }

    /** Moves a file or a folder to the Recycle Bin, if its preview allows it. */
    @Transactional(readOnly = true)
    public void recycle(Long projectId, String relative) {
        Path root = writableRoot(projectId);
        recyclePreview(projectId, relative).requireAllowed();
        Path target = existing(root, relative);
        try {
            desktop.moveToRecycleBin(target);
        } catch (IOException refused) {
            throw new WorkspaceEntryInUseException(refused.getMessage());
        }
    }

    // --- Explorer ------------------------------------------------------------------------

    /** An Explorer window on the item, selected; an empty path is the project folder. Changes nothing. */
    @Transactional(readOnly = true)
    public void reveal(Long projectId, String relative) {
        Path root = existingRoot(projectId);
        desktop.reveal(isRoot(relative) ? root : existing(root, relative));
    }

    // --- rules ---------------------------------------------------------------------------

    /** A name Windows keeps as written, for one entry of one folder. */
    static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new RequestValidationException("name", "is required");
        }
        if (name.length() > 255) {
            throw new RequestValidationException("name", "must be at most 255 characters");
        }
        if (FORBIDDEN_CHARACTERS.matcher(name).find()) {
            throw new RequestValidationException("name", "cannot contain \\ / : * ? \" < > | or control characters");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new RequestValidationException("name", "cannot be . or ..");
        }
        if (name.endsWith(" ") || name.endsWith(".") || name.startsWith(" ")) {
            throw new RequestValidationException("name", "cannot start with a space or end with a space or a dot: Windows would change it");
        }
        if (DEVICE.matcher(name).matches()) {
            throw new RequestValidationException("name", "is a device name Windows reserves");
        }
        if (name.contains(ManagedFiles.STAGED_MARK) || name.contains(".aicos-renaming-")) {
            throw new RequestValidationException("name", "is reserved by AI Company OS");
        }
        return name;
    }

    static boolean isStructure(String rel) {
        return STRUCTURE.contains(rel.toLowerCase(Locale.ROOT));
    }

    static boolean insideGit(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        return lower.equals(".git") || lower.startsWith(".git/");
    }

    private static void refuseProtected(String rel, String verb) {
        if (isStructure(rel)) {
            throw new WorkspacePathRefusedException("'" + rel + "' è la struttura del workspace: non viene " + verb
                    + " da qui (ADR-035 §6)");
        }
        if (insideGit(rel)) {
            throw new WorkspacePathRefusedException("'" + rel + "' è il repository git: non viene " + verb + " da qui");
        }
    }

    /** A symbolic link or a Windows junction, seen without following it. */
    private static boolean isLink(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isSymbolicLink() || a.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /** The records that point at this path or, for a folder, at something under it. */
    private List<Use> usesOf(Long projectId, String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        List<Use> all = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT title, document_path FROM tasks "
                + "WHERE project_id = ? AND document_path IS NOT NULL", projectId)) {
            all.add(new Use((String) row.get("document_path"),
                    "È il documento della task «" + row.get("title") + "»: elimina la task, se vuoi toglierlo."));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT number, title, document_path FROM project_phases "
                + "WHERE project_id = ?", projectId)) {
            all.add(new Use((String) row.get("document_path"),
                    "È il documento della fase " + row.get("number") + " «" + row.get("title") + "» del piano."));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT DISTINCT t.title, h.document_path FROM task_handoffs h "
                + "JOIN tasks t ON t.id = h.task_id WHERE t.project_id = ?", projectId)) {
            all.add(new Use((String) row.get("document_path"),
                    "Un handoff della task «" + row.get("title") + "» lo registra: resta finché la task esiste."));
        }
        return all.stream().filter(use -> {
            String path = use.path().replace('\\', '/').toLowerCase(Locale.ROOT);
            return path.equals(lower) || path.startsWith(lower + "/");
        }).toList();
    }

    /** Counts what a folder holds, without following links, and stops past the limits. */
    private static Contents sizeOf(Path folder) {
        int[] counts = {0, 0};
        long[] total = {0};
        boolean[] over = {false};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(folder)) {
                        counts[1]++;
                    }
                    return check();
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    counts[0]++;
                    total[0] += attrs.isRegularFile() ? attrs.size() : 0;
                    return check();
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    counts[0]++;
                    return check();
                }

                private FileVisitResult check() {
                    if (counts[0] + counts[1] > MAX_RECYCLE_ENTRIES || total[0] > MAX_RECYCLE_BYTES) {
                        over[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("The folder could not be read: " + e.getMessage());
        }
        return new Contents(counts[0], counts[1], total[0], over[0]);
    }

    private Project project(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private Path writableRoot(Long projectId) {
        if (project(projectId).isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        return existingRoot(projectId);
    }

    private Path existingRoot(Long projectId) {
        Path root = workspaces.workspaceOf(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new WorkspaceUnavailableException("The project folder " + root + " does not exist");
        }
        return root;
    }

    /** A path inside the folder that is there -- a link counts as there, whatever it points at. */
    private static Path existing(Path root, String relative) {
        if (isRoot(relative)) {
            throw new WorkspacePathRefusedException("The project folder itself is not changed from the explorer");
        }
        Path path = WorkspacePaths.inside(root, relative);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileNotFoundException("No file or folder '" + relative + "' in the project folder");
        }
        return path;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRoot(String relative) {
        return relative == null || relative.isBlank() || relative.strip().equals(".") || relative.strip().equals("/");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ITALIAN, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ITALIAN, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.ITALIAN, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
