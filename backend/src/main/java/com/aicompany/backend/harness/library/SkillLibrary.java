package com.aicompany.backend.harness.library;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.harness.exception.HarnessResourceKeyConflictException;
import com.aicompany.backend.harness.exception.HarnessResourceNotFoundException;
import com.aicompany.backend.harness.exception.LibraryProblemException;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.repository.HarnessResourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The skills and knowledge of the ecosystem as files (ADR-026): readable,
 * editable by hand or from the console, versionable with git, and still
 * importable from the web. The file is the source of truth for the content; the
 * {@code harness_resources} table is its index.
 *
 * <pre>
 * &lt;library&gt;/skills/&lt;key&gt;/SKILL.md
 * &lt;library&gt;/knowledge/&lt;key&gt;.md
 * </pre>
 *
 * Every path is computed from a validated key, never taken from a request.
 */
@Service
@Transactional
public class SkillLibrary {

    public static final int MAX_DOCUMENT = 200_000;

    public record FileView(String relativePath, String absolutePath, boolean exists, String content, Instant modified) {
    }

    public record SyncReport(Path root, List<String> created, List<String> updated, List<String> invalid) {
    }

    private final Path root;
    private final HarnessResourceRepository resources;
    private final RemoteText remote;

    public SkillLibrary(@Value("${aicos.library.root:${aicos.home:${user.home}/.aicos}/library}") String root,
                        HarnessResourceRepository resources, RemoteText remote) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.resources = resources;
        this.remote = remote;
    }

    @Transactional(readOnly = true)
    public Path root() {
        return root;
    }

    public static boolean fileBacked(HarnessResource.Kind kind) {
        return kind == HarnessResource.Kind.SKILL || kind == HarnessResource.Kind.KNOWLEDGE;
    }

    public static String relativePath(HarnessResource.Kind kind, String key) {
        return kind == HarnessResource.Kind.SKILL ? "skills/" + key + "/SKILL.md" : "knowledge/" + key + ".md";
    }

    @Transactional(readOnly = true)
    public FileView view(String key) {
        HarnessResource resource = load(key);
        requireFileBacked(resource);
        String relative = relativePath(resource.getKind(), resource.getKey());
        Path path = pathOf(relative);
        try {
            if (!Files.isRegularFile(path)) {
                return new FileView(relative, path.toString(), false, null, null);
            }
            return new FileView(relative, path.toString(), true, Files.readString(path, StandardCharsets.UTF_8),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            throw LibraryProblemException.unavailable("'" + relative + "' could not be read: " + e.getMessage());
        }
    }

    /** The instructions of a skill, for prompts and handoffs; empty when it has no file yet. */
    @Transactional(readOnly = true)
    public Optional<String> body(HarnessResource resource) {
        if (!fileBacked(resource.getKind())) {
            return Optional.empty();
        }
        Path path = pathOf(relativePath(resource.getKind(), resource.getKey()));
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            try {
                return Optional.of(SkillDocument.parse(text, resource.getKey()).body());
            } catch (LibraryProblemException invalid) {
                return Optional.of(text);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Writes the file of a catalog entry that has none, from what the index knows. Never overwrites. */
    public HarnessResource materialize(String key) {
        HarnessResource resource = resources.findByKeyForUpdate(key).orElseThrow(() -> notFound(key));
        requireFileBacked(resource);
        String relative = relativePath(resource.getKind(), resource.getKey());
        if (!Files.exists(pathOf(relative))) {
            String body = "# " + resource.getName() + "\n\n"
                    + (resource.getDescription() == null ? "" : resource.getDescription() + "\n\n")
                    + "## Istruzioni\n\n"
                    + "Scrivi qui, in Markdown, come l'agente deve applicare questa "
                    + (resource.getKind() == HarnessResource.Kind.SKILL ? "skill" : "conoscenza")
                    + ": regole, esempi, cose da evitare.\n"
                    + (resource.getConfiguration() == null ? "" : "\n```\n" + resource.getConfiguration().strip() + "\n```\n");
            write(relative, new SkillDocument(resource.getKey(), resource.getName(), resource.getKind(),
                    resource.getDescription(), resource.getTags(), resource.getSourceUrl(), body).render());
        }
        resource.linkFile(relative, null);
        resources.flush();
        return resource;
    }

    /** The operator saved the file from the console: validate, write, re-index. ADR-009 on the index row. */
    public HarnessResource save(String key, String content, Precondition precondition) {
        HarnessResource resource = resources.findByKeyForUpdate(key).orElseThrow(() -> notFound(key));
        precondition.requireSatisfiedBy(resource.getVersion());
        requireFileBacked(resource);
        requireSize(content);
        SkillDocument document = SkillDocument.parse(content, resource.getKey());
        if (document.kind() != resource.getKind()) {
            throw LibraryProblemException.invalidDocument("'kind' must stay " + resource.getKind());
        }
        String relative = relativePath(resource.getKind(), resource.getKey());
        write(relative, content);
        resource.describe(document.name(), document.description(), document.tags(), document.source());
        resource.linkFile(relative, null);
        resources.flush();
        return resource;
    }

    /** A new skill or knowledge entry, written as a file and indexed. */
    public HarnessResource create(String content, HarnessResource.Origin origin) {
        requireSize(content);
        SkillDocument document = SkillDocument.parse(content, null);
        if (resources.existsByKey(document.key())) {
            throw new HarnessResourceKeyConflictException("The catalog already has a resource '" + document.key() + "'");
        }
        String relative = relativePath(document.kind(), document.key());
        write(relative, content);
        HarnessResource resource = new HarnessResource(document.key(), document.kind(), document.name(),
                document.description(), document.tags(), document.source(), null, null);
        resource.linkFile(relative, origin);
        return resources.saveAndFlush(resource);
    }

    /**
     * Reads the library folder and brings the index in line with it: files
     * nobody indexed become entries (origin FILE), indexed files update their
     * entry. Nothing is deleted.
     */
    public SyncReport sync() {
        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (Path file : candidates()) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String expected = relative.startsWith("skills/") ? relative.split("/")[1]
                        : relative.substring("knowledge/".length(), relative.length() - ".md".length());
                SkillDocument document = SkillDocument.parse(text, expected);
                if (!relativePath(document.kind(), document.key()).equals(relative)) {
                    invalid.add(relative + ": the kind does not match the folder");
                    continue;
                }
                Optional<HarnessResource> existing = resources.findByKeyForUpdate(document.key());
                if (existing.isPresent()) {
                    if (existing.get().getKind() != document.kind()) {
                        invalid.add(relative + ": '" + document.key() + "' is already a " + existing.get().getKind());
                        continue;
                    }
                    existing.get().describe(document.name(), document.description(), document.tags(), document.source());
                    existing.get().linkFile(relative, null);
                    updated.add(relative);
                } else {
                    HarnessResource resource = new HarnessResource(document.key(), document.kind(), document.name(),
                            document.description(), document.tags(), document.source(), null, null);
                    resource.linkFile(relative, HarnessResource.Origin.FILE);
                    resources.save(resource);
                    created.add(relative);
                }
            } catch (LibraryProblemException e) {
                invalid.add(relative + ": " + e.getMessage());
            } catch (IOException e) {
                invalid.add(relative + ": unreadable (" + e.getMessage() + ")");
            }
        }
        resources.flush();
        return new SyncReport(root, created, updated, invalid);
    }

    /**
     * Imports a skill from an HTTPS URL (a raw SKILL.md, or a GitHub "blob" page
     * which is rewritten to its raw file). Refused: other schemes, loopback,
     * private and link-local addresses, documents over 256 KB, HTML pages.
     */
    public HarnessResource importFrom(String url) {
        URI uri = validated(url);
        RemoteText.Fetched fetched = remote.fetch(uri, 256 * 1024);
        String body = fetched.body();
        if (fetched.contentType().toLowerCase(Locale.ROOT).contains("html") || body.stripLeading().startsWith("<")) {
            throw LibraryProblemException.importRefused("The URL returned a web page, not a Markdown document: use the raw file");
        }
        String content = body.replace("\r\n", "\n");
        if (!content.startsWith("---\n")) {
            // A plain Markdown file: give it the frontmatter it lacks.
            String file = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1).replaceAll("(?i)\\.md$", "");
            if (file.equalsIgnoreCase("SKILL")) {
                String path = uri.getPath();
                String[] parts = path.split("/");
                file = parts.length >= 2 ? parts[parts.length - 2] : "skill";
            }
            String key = file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (key.length() < 2) {
                key = "imported-skill";
            }
            String title = content.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).strip())
                    .orElse(key);
            content = new SkillDocument(key.length() > 64 ? key.substring(0, 64) : key,
                    title.length() > 120 ? title.substring(0, 120) : title, HarnessResource.Kind.SKILL, null,
                    List.of("importata"), uri.toString(), content).render();
        }
        SkillDocument document = SkillDocument.parse(content, null);
        if (document.source() == null) {
            content = new SkillDocument(document.key(), document.name(), document.kind(), document.description(),
                    document.tags(), uri.toString(), document.body()).render();
        }
        return create(content, HarnessResource.Origin.WEB);
    }

    static URI validated(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw LibraryProblemException.importRefused("Not a URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw LibraryProblemException.importRefused("Only https:// URLs of a public host can be imported");
        }
        // github.com/<owner>/<repo>/blob/<ref>/<path>  ->  raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
        if (uri.getHost().equalsIgnoreCase("github.com") && uri.getPath().contains("/blob/")) {
            uri = URI.create("https://raw.githubusercontent.com" + uri.getPath().replaceFirst("/blob/", "/"));
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocalV6(address)) {
                    throw LibraryProblemException.importRefused("The host resolves to a local or private address");
                }
            }
        } catch (UnknownHostException e) {
            throw LibraryProblemException.importRefused("The host '" + uri.getHost() + "' does not resolve");
        }
        return uri;
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    // --- files --------------------------------------------------------------------------

    private List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        Path skills = root.resolve("skills");
        Path knowledge = root.resolve("knowledge");
        try {
            if (Files.isDirectory(skills)) {
                try (Stream<Path> dirs = Files.list(skills)) {
                    dirs.filter(Files::isDirectory).map(d -> d.resolve("SKILL.md")).filter(Files::isRegularFile)
                            .filter(f -> !Files.isSymbolicLink(f.getParent())).sorted().forEach(out::add);
                }
            }
            if (Files.isDirectory(knowledge)) {
                try (Stream<Path> files = Files.list(knowledge)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".md")).filter(Files::isRegularFile)
                            .filter(f -> !Files.isSymbolicLink(f)).sorted().forEach(out::add);
                }
            }
        } catch (IOException e) {
            throw LibraryProblemException.unavailable("The library " + root + " could not be listed: " + e.getMessage());
        }
        return out;
    }

    private Path pathOf(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw LibraryProblemException.unavailable("A library path left the library");
        }
        return path;
    }

    private void write(String relative, String content) {
        Path path = pathOf(relative);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw LibraryProblemException.unavailable("'" + relative + "' could not be written: " + e.getMessage());
        }
    }

    private static void requireSize(String content) {
        if (content == null || content.isBlank()) {
            throw LibraryProblemException.invalidDocument("The document is empty");
        }
        if (content.length() > MAX_DOCUMENT) {
            throw LibraryProblemException.invalidDocument("The document is larger than " + MAX_DOCUMENT + " characters");
        }
    }

    private static void requireFileBacked(HarnessResource resource) {
        if (!fileBacked(resource.getKind())) {
            throw LibraryProblemException.notFileBacked(resource.getKey());
        }
    }

    private HarnessResource load(String key) {
        return resources.findByKey(key).orElseThrow(() -> notFound(key));
    }

    private static HarnessResourceNotFoundException notFound(String key) {
        return new HarnessResourceNotFoundException("No resource '" + key + "' in the catalog");
    }
}
