package com.aicompany.backend.workspace.service;

import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves a path a caller sent against a project workspace, and refuses every
 * way out of it (ADR-020 W2) and every file the API does not deal in (W3).
 *
 * <p>Pure, apart from resolving the real path of the deepest existing parent --
 * which is what closes the symbolic-link way out: a link inside the workspace
 * that points elsewhere resolves outside, and is refused.
 */
public final class WorkspacePaths {

    /** Where the API may write documents. */
    // PHASE 17 (ADR-025 §4): the rules folders of Junie and Continue, and the TASK.md of an inbox folder,
    // are where a handoff prepares the environment of the tool that will do the work.
    static final List<String> WRITABLE_ROOTS = List.of("docs/", "tasks/", "references/", ".aicos/", ".junie/",
            ".continue/rules/");
    static final List<String> WRITABLE_FILES = List.of("MASTER_PROMPT.md", "AGENTS.md", "CLAUDE.md", "TASK.md");
    static final List<String> TEXT_EXTENSIONS = List.of(".md", ".json", ".txt");
    static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".webp", ".gif");

    /** Windows device names and anything with a drive, a colon stream or a control character. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(^|[/\\\\])(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\.|$|[/\\\\])|:|[\\u0000-\\u001f]",
            Pattern.CASE_INSENSITIVE);

    private WorkspacePaths() {
    }

    /** A path that is inside the workspace, whatever it names. */
    public static Path inside(Path workspace, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new WorkspacePathRefusedException("A path is required");
        }
        String normalizedText = relative.replace('\\', '/').strip();
        if (normalizedText.startsWith("/") || FORBIDDEN.matcher(normalizedText).find()) {
            throw new WorkspacePathRefusedException("'" + relative + "' is not a relative path inside the project");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = root.resolve(normalizedText).normalize();
        } catch (InvalidPathException e) {
            throw new WorkspacePathRefusedException("'" + relative + "' is not a valid path");
        }
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new WorkspacePathRefusedException("'" + relative + "' leaves the project folder");
        }
        requireRealPathInside(root, candidate, relative);
        return candidate;
    }

    /** ADR-020 W3: a text document under one of the writable roots. */
    public static Path writableDocument(Path workspace, String relative) {
        Path path = inside(workspace, relative);
        String rel = relativeText(workspace, path);
        boolean allowedPlace = WRITABLE_FILES.contains(rel) || WRITABLE_ROOTS.stream().anyMatch(rel::startsWith);
        if (!allowedPlace || !hasExtension(rel, TEXT_EXTENSIONS)) {
            throw new WorkspacePathRefusedException(
                    "'" + relative + "' is not a project document (text under docs/, tasks/, references/, .aicos/ "
                            + "or MASTER_PROMPT.md, AGENTS.md, CLAUDE.md)");
        }
        return path;
    }

    public static String relativeText(Path workspace, Path path) {
        return workspace.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/');
    }

    public static boolean hasExtension(String name, List<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    private static void requireRealPathInside(Path root, Path candidate, String relative) {
        try {
            Path realRoot = Files.exists(root) ? root.toRealPath() : root;
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && existing.startsWith(root) && !existing.toRealPath().startsWith(realRoot)) {
                throw new WorkspacePathRefusedException("'" + relative + "' resolves outside the project folder");
            }
        } catch (IOException e) {
            throw new WorkspacePathRefusedException("'" + relative + "' cannot be resolved");
        }
    }
}
