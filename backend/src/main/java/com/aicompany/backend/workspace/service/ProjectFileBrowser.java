package com.aicompany.backend.workspace.service;

import com.aicompany.backend.workspace.exception.WorkspaceFileNotFoundException;
import com.aicompany.backend.workspace.exception.WorkspacePathRefusedException;
import com.aicompany.backend.workspace.exception.WorkspaceUnavailableException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PHASE 33 (ADR-039): every file of a project's folder, read-only, for the console's
 * file explorer -- one folder at a time, and one file at a time.
 *
 * <p>Where ADR-020 W3 limits what the API reads to the project's documents and
 * reference images, browsing reads any file -- but nothing more: the same way out of
 * the folder is refused (W2, links included), nothing is written, a file that looks
 * like it holds secrets is listed but never read, and only images are served as bytes.
 */
@Service
public class ProjectFileBrowser {

    public enum EntryKind { DIRECTORY, FILE, LINK }

    public enum ViewKind { TEXT, IMAGE, BINARY, WITHHELD, TOO_LARGE }

    public record Entry(String name, String path, EntryKind kind, Long size, Instant modifiedAt) {
    }

    public record Listing(String path, List<Entry> entries, boolean truncated) {
    }

    public record View(String path, String name, long size, Instant modifiedAt, ViewKind kind, String contentType,
                       String content, String reason) {
    }

    public record Raw(String contentType, byte[] bytes) {
    }

    static final int MAX_ENTRIES = 5000;
    static final long MAX_TEXT_BYTES = 2_000_000;
    static final long MAX_IMAGE_BYTES = 10_000_000;
    private static final int SNIFF_BYTES = 8000;

    /** Images the console shows. SVG is not one: it is text that can carry a script, so it is shown as text. */
    private static final Map<String, String> IMAGES = Map.of(".png", "image/png", ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg", ".gif", "image/gif", ".webp", "image/webp", ".bmp", "image/bmp", ".ico", "image/x-icon");

    /** File names that usually hold credentials: listed, never read. Templates of them are read. */
    private static final Pattern SECRET = Pattern.compile(
            "^\\.env(\\..+)?$|^local\\.env$|^\\.git-credentials$|^[._]?netrc$|^\\.npmrc$|^\\.pypirc$"
                    + "|^credentials(\\.json)?$|^secrets?\\.(json|ya?ml|toml|env|properties)$"
                    + "|^id_(rsa|dsa|ecdsa|ed25519)$|\\.(pem|key|p12|pfx|jks|keystore|ppk|kdbx)$");
    private static final Pattern SECRET_TEMPLATE = Pattern.compile("^\\.env\\.(example|sample|template|dist|defaults)$");

    private final ProjectWorkspaceService workspaces;

    public ProjectFileBrowser(ProjectWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /** One folder: its entries, folders first, then by name. {@code relative} empty is the project folder. */
    public Listing list(Long projectId, String relative) {
        Path root = existingRoot(projectId);
        Path folder = isRoot(relative) ? root : WorkspacePaths.inside(root, relative);
        if (!Files.isDirectory(folder)) {
            throw new WorkspaceFileNotFoundException("No folder '" + relative + "' in the project folder");
        }
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (entries.size() == MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(entry(root, child));
            }
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + relative + "' could not be listed");
        }
        entries.sort(Comparator.comparing((Entry e) -> e.kind() == EntryKind.FILE)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::name));
        return new Listing(isRoot(relative) ? "" : WorkspacePaths.relativeText(root, folder), entries, truncated);
    }

    /** One file: its text, or why the console shows something else. */
    public View view(Long projectId, String relative) {
        Path root = existingRoot(projectId);
        Path file = regularFile(root, relative);
        String rel = WorkspacePaths.relativeText(root, file);
        String name = file.getFileName().toString();
        BasicFileAttributes attributes = attributes(file, rel);
        long size = attributes.size();
        Instant modified = attributes.lastModifiedTime().toInstant();
        if (withheld(name)) {
            return new View(rel, name, size, modified, ViewKind.WITHHELD, null, null,
                    "Il nome indica un file di credenziali: non viene letto. Aprilo con il tuo editor se ti serve.");
        }
        String image = imageType(name);
        if (image != null) {
            return size > MAX_IMAGE_BYTES
                    ? new View(rel, name, size, modified, ViewKind.TOO_LARGE, image, null, "Immagine oltre 10 MB")
                    : new View(rel, name, size, modified, ViewKind.IMAGE, image, null, null);
        }
        if (size > MAX_TEXT_BYTES) {
            return new View(rel, name, size, modified, ViewKind.TOO_LARGE, null, null, "File oltre 2 MB");
        }
        byte[] bytes = read(file, rel);
        String text = text(bytes);
        if (text == null) {
            return new View(rel, name, size, modified, ViewKind.BINARY, null, null,
                    "File binario: non è testo da mostrare");
        }
        return new View(rel, name, size, modified, ViewKind.TEXT, "text/plain; charset=utf-8", text, null);
    }

    /** The bytes of an image, for the console to show it; nothing else is served as bytes. */
    public Raw image(Long projectId, String relative) {
        Path root = existingRoot(projectId);
        Path file = regularFile(root, relative);
        String rel = WorkspacePaths.relativeText(root, file);
        String name = file.getFileName().toString();
        String type = imageType(name);
        if (type == null || withheld(name)) {
            throw new WorkspacePathRefusedException("'" + rel + "' is not an image the explorer shows");
        }
        if (attributes(file, rel).size() > MAX_IMAGE_BYTES) {
            throw new WorkspacePathRefusedException("'" + rel + "' is larger than 10 MB");
        }
        return new Raw(type, read(file, rel));
    }

    static boolean withheld(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET.matcher(lower).find() && !SECRET_TEMPLATE.matcher(lower).matches();
    }

    /** UTF-8 text without NUL bytes, its BOM removed; null when the bytes are not that. */
    static String text(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, SNIFF_BYTES); i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    private Path existingRoot(Long projectId) {
        Path root = workspaces.workspaceOf(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new WorkspaceUnavailableException("The project folder " + root + " does not exist");
        }
        return root;
    }

    private static Path regularFile(Path root, String relative) {
        Path file = WorkspacePaths.inside(root, relative);
        if (Files.isDirectory(file)) {
            throw new WorkspacePathRefusedException("'" + relative + "' is a folder");
        }
        if (!Files.isRegularFile(file)) {
            throw new WorkspaceFileNotFoundException("No file '" + relative + "' in the project folder");
        }
        return file;
    }

    private static Entry entry(Path root, Path child) {
        String name = child.getFileName().toString();
        String rel = WorkspacePaths.relativeText(root, child);
        try {
            BasicFileAttributes a = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // A Windows junction is "other" without following links: it is a link like a symbolic one.
            EntryKind kind = a.isSymbolicLink() || a.isOther() ? EntryKind.LINK
                    : a.isDirectory() ? EntryKind.DIRECTORY : EntryKind.FILE;
            return new Entry(name, rel, kind, kind == EntryKind.FILE ? a.size() : null,
                    a.lastModifiedTime().toInstant());
        } catch (IOException e) {
            return new Entry(name, rel, EntryKind.FILE, null, null);
        }
    }

    private static BasicFileAttributes attributes(Path file, String rel) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + rel + "' could not be read");
        }
    }

    private static byte[] read(Path file, String rel) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new WorkspaceUnavailableException("'" + rel + "' could not be read");
        }
    }

    private static String imageType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return IMAGES.entrySet().stream().filter(e -> lower.endsWith(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static boolean isRoot(String relative) {
        return relative == null || relative.isBlank() || relative.strip().equals(".") || relative.strip().equals("/");
    }
}
