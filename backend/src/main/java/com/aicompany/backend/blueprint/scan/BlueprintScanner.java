package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Component;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.FileAnalysis;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Reference;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import com.aicompany.backend.graph.code.CodeGraphScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Code into Blueprint components and relations (ADR-033 §4-5): walks a project
 * folder, gives every file to the plugin that reads its language, and resolves
 * the references across files. Pure: it reads files and returns values, and
 * writes nothing -- the reconciliation with what is stored is
 * {@code BlueprintScanService}'s.
 *
 * <p>Same bounds as the code graph of ADR-030: at most {@link CodeGraphScanner#MAX_FILES}
 * files of at most {@link CodeGraphScanner#MAX_BYTES}, generated and vendored
 * folders skipped, symbolic links not followed.
 */
public final class BlueprintScanner {

    private static final Logger log = LoggerFactory.getLogger(BlueprintScanner.class);

    public static final List<BlueprintLanguagePlugin> DEFAULT_PLUGINS =
            List.of(new JavaAstPlugin(), new TypeScriptPlugin(), new PythonPlugin(), new SqlMigrationPlugin());

    /** A resolved relation between two component keys. */
    public record Relation(String sourceKey, String targetKey, RelationType relation, String label, String codePath,
                           Integer line, String excerpt) {
    }

    /**
     * {@code unresolvedSamples}: the first references of the project that named
     * nothing, as {@code file:line -> first candidate}, for the scan's report.
     */
    public record Result(List<Component> components, List<Relation> relations, int files, boolean truncated,
                         Map<String, Integer> languages, Map<String, String> fidelity, int unresolved,
                         List<String> unresolvedSamples) {
    }

    private final List<BlueprintLanguagePlugin> plugins;

    public BlueprintScanner(List<BlueprintLanguagePlugin> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    public static BlueprintScanner withDefaultPlugins() {
        return new BlueprintScanner(DEFAULT_PLUGINS);
    }

    public Result scan(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        boolean[] truncated = {false};
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
                if (!attrs.isRegularFile() || attrs.size() > CodeGraphScanner.MAX_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(root, file);
                if (plugins.stream().anyMatch(p -> p.supports(rel))) {
                    if (files.size() >= CodeGraphScanner.MAX_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        // Migrations in version order, so a later ALTER adds to an earlier CREATE.
        files.sort(Comparator.comparing(p -> sortKey(relative(root, p))));

        List<SourceFile> sources = new ArrayList<>();
        for (Path file : files) {
            try {
                sources.add(new SourceFile(relative(root, file), Files.readString(file, StandardCharsets.UTF_8)));
            } catch (MalformedInputException notUtf8) {
                log.debug("Blueprint: {} is not UTF-8, skipped", file);
            }
        }
        return analyze(sources, truncated[0]);
    }

    /** The files already read: what {@link #scan} does after the walk, and what the tests call directly. */
    public Result analyze(List<SourceFile> sources, boolean truncated) {
        Map<String, Component> components = new LinkedHashMap<>();
        List<Reference> references = new ArrayList<>();
        List<BlueprintLanguagePlugin.AccessRule> accessRules = new ArrayList<>();
        Map<String, Integer> languages = new TreeMap<>();
        Map<String, String> fidelity = new TreeMap<>();
        for (SourceFile source : sources) {
            BlueprintLanguagePlugin plugin = plugins.stream().filter(p -> p.supports(source.path())).findFirst().orElseThrow();
            FileAnalysis analysis;
            try {
                analysis = plugin.analyze(source);
            } catch (RuntimeException unreadable) {
                // One file a plugin cannot read does not cost the scan of the project.
                log.warn("Blueprint: {} could not be analysed by the {} plugin: {}", source.path(), plugin.language(),
                        unreadable.toString());
                continue;
            }
            languages.merge(plugin.language(), 1, Integer::sum);
            fidelity.put(plugin.language(), plugin.fidelity());
            for (Component c : analysis.components()) {
                components.merge(c.key(), c, BlueprintScanner::merge);
            }
            references.addAll(analysis.references());
            accessRules.addAll(analysis.accessRules());
        }
        // PHASE 32 (TD-62): an endpoint no annotation guards is decided by the security
        // configuration's rules by path -- the first that matches, as Spring Security does.
        if (!accessRules.isEmpty()) {
            components.replaceAll((k, c) -> c.kind() == NodeKind.ENDPOINT ? withPathRule(c, accessRules) : c);
        }
        // A table only ALTERed (created elsewhere, or outside the scan) still reads well.
        components.replaceAll((k, c) -> c.description() == null
                ? new Component(c.key(), c.kind(), c.name(), c.filePath(), c.language(),
                "Tabella " + c.name() + ", modificata da migrazioni.", c.pseudocode(), c.structure(), c.startLine(), c.endLine())
                : c);

        int[] unresolved = {0};
        List<String> samples = new ArrayList<>();
        List<Relation> resolved = new ArrayList<>();
        List<Component> endpoints = components.values().stream().filter(c -> c.kind() == NodeKind.ENDPOINT).toList();
        for (Reference r : references) {
            if (!components.containsKey(r.sourceKey())) {
                continue;
            }
            List<String> targets = resolve(r, components, endpoints);
            if (targets.isEmpty()) {
                if (r.internal()) {
                    unresolved[0]++;
                    if (samples.size() < 20) {
                        samples.add(r.codePath() + ":" + r.line() + " -> " + r.targetCandidates().get(0));
                    }
                }
                continue;
            }
            for (String target : targets) {
                if (!target.equals(r.sourceKey())) {
                    resolved.add(new Relation(r.sourceKey(), target, refine(r.relation(), components.get(r.sourceKey()),
                            components.get(target)), r.label(), r.codePath(), r.line(), r.excerpt()));
                }
            }
        }
        List<Relation> relations = consolidate(resolved);
        Map<String, Component> withTests = relatedTests(components, relations);
        return new Result(List.copyOf(withTests.values()), relations, sources.size(), truncated, languages, fidelity,
                unresolved[0], List.copyOf(samples));
    }

    // --- resolution --------------------------------------------------------------

    private static List<String> resolve(Reference r, Map<String, Component> components, List<Component> endpoints) {
        if (r.targetCandidates().size() == 1 && r.targetCandidates().get(0).startsWith("route:")) {
            String spec = r.targetCandidates().get(0).substring("route:".length());
            int space = spec.indexOf(' ');
            String method = space > 0 ? spec.substring(0, space) : "*";
            String route = space > 0 ? spec.substring(space + 1) : spec;
            List<String> matched = new ArrayList<>();
            for (Component e : endpoints) {
                CodeStructure s = e.structure();
                if (s.route() != null && routeMatches(route, s.route())
                        && (method.equals("*") || method.equalsIgnoreCase(s.httpMethod()))) {
                    matched.add(e.key());
                }
            }
            // A wildcard method that matches several endpoints of one route is
            // still one call of one route: keep them, they are all it can mean.
            return matched;
        }
        for (String candidate : r.targetCandidates()) {
            if (components.containsKey(candidate)) {
                return List.of(candidate);
            }
        }
        return List.of();
    }

    /**
     * {@code /api/projects/{}/code-graph} (a call) against
     * {@code /api/projects/{id}/code-graph} (a route): segment by segment, a
     * variable on either side matches any one segment.
     */
    public static boolean routeMatches(String called, String route) {
        String[] a = trim(called).split("/");
        String[] b = trim(route).split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean varA = a[i].contains("{");
            boolean varB = b[i].startsWith("{") && b[i].endsWith("}");
            if (!varA && !varB && !a[i].equals(b[i])) {
                return false;
            }
            if (varA && !a[i].equals("{}") && !varB) {
                // "x{}" -- a literal glued to a variable: only a variable segment can hold it
                return false;
            }
        }
        return true;
    }

    private static String trim(String route) {
        String r = route.startsWith("/") ? route.substring(1) : route;
        return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }

    /**
     * The relation a reference means once both ends are known: a service calling a
     * repository is SERVICE_TO_REPOSITORY, an import of a hook is a USE, a test's
     * import is what it tests, an endpoint's call to something that is not a
     * service is a plain CALL.
     */
    static RelationType refine(RelationType relation, Component source, Component target) {
        NodeKind from = source.kind();
        NodeKind to = target.kind();
        if (relation == RelationType.CALLS && from == NodeKind.SERVICE && to == NodeKind.REPOSITORY) {
            return RelationType.SERVICE_TO_REPOSITORY;
        }
        if (relation == RelationType.ENDPOINT_TO_SERVICE && to != NodeKind.SERVICE) {
            return RelationType.CALLS;
        }
        if (relation == RelationType.IMPORTS) {
            if (from == NodeKind.TEST) {
                return RelationType.TEST_COVERS;
            }
            if (EnumSet.of(NodeKind.HOOK, NodeKind.STORE, NodeKind.COMPONENT, NodeKind.CLASS, NodeKind.FUNCTION).contains(to)) {
                return RelationType.USES;
            }
        }
        return relation;
    }

    /**
     * One edge per (source, target, relation), labels merged; and no USES next to
     * a CALLS (or a SERVICE_TO_REPOSITORY) between the same two blocks, nor an
     * IMPORTS next to a USES -- the stronger relation already says the weaker one,
     * and two parallel lines say nothing more.
     */
    static List<Relation> consolidate(List<Relation> relations) {
        Map<String, Relation> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> labels = new HashMap<>();
        for (Relation r : relations) {
            String k = r.sourceKey() + "\u0000" + r.targetKey() + "\u0000" + r.relation();
            byKey.putIfAbsent(k, r);
            if (r.label() != null && !r.label().isBlank()) {
                labels.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(r.label());
            }
        }
        Set<String> strong = new HashSet<>();
        Set<String> uses = new HashSet<>();
        for (Relation r : byKey.values()) {
            String pair = r.sourceKey() + "\u0000" + r.targetKey();
            if (r.relation() == RelationType.CALLS || r.relation() == RelationType.SERVICE_TO_REPOSITORY
                    || r.relation() == RelationType.ENDPOINT_TO_SERVICE) {
                strong.add(pair);
            }
            if (r.relation() == RelationType.USES) {
                uses.add(pair);
            }
        }
        List<Relation> out = new ArrayList<>();
        for (Map.Entry<String, Relation> e : byKey.entrySet()) {
            Relation r = e.getValue();
            String pair = r.sourceKey() + "\u0000" + r.targetKey();
            if (r.relation() == RelationType.USES && strong.contains(pair)) {
                continue;
            }
            if (r.relation() == RelationType.IMPORTS && (uses.contains(pair) || strong.contains(pair))) {
                continue;
            }
            String label = Optional.ofNullable(labels.get(e.getKey())).map(l -> String.join(", ", l)).orElse(null);
            if (label != null && label.length() > 255) {
                label = label.substring(0, 254) + "…";
            }
            out.add(new Relation(r.sourceKey(), r.targetKey(), r.relation(), label, r.codePath(), r.line(), r.excerpt()));
        }
        return out;
    }

    /** The tests that cover a component, written into its structure. */
    private static Map<String, Component> relatedTests(Map<String, Component> components, List<Relation> relations) {
        Map<String, List<String>> tests = new HashMap<>();
        for (Relation r : relations) {
            if (r.relation() == RelationType.TEST_COVERS) {
                tests.computeIfAbsent(r.targetKey(), k -> new ArrayList<>()).add(components.get(r.sourceKey()).name());
            }
        }
        Map<String, Component> out = new LinkedHashMap<>(components);
        tests.forEach((key, names) -> {
            Component c = out.get(key);
            List<String> distinct = List.copyOf(new LinkedHashSet<>(names));
            out.put(key, new Component(c.key(), c.kind(), c.name(), c.filePath(), c.language(), c.description(),
                    c.pseudocode(), c.structure().toBuilder().relatedTests(distinct).build(), c.startLine(), c.endLine()));
        });
        return out;
    }

    /** Two components with one key: a table created in one migration and altered in others. */
    /**
     * PHASE 32 (TD-62): the endpoint's authorization from the first rule by path that
     * matches its method and route; unchanged when an annotation already decides, or
     * when no rule matches.
     */
    static Component withPathRule(Component endpoint, List<BlueprintLanguagePlugin.AccessRule> rules) {
        CodeStructure s = endpoint.structure();
        if (s == null || s.route() == null || !JavaAstPlugin.GLOBAL_RULE.equals(s.authorization())) {
            return endpoint;
        }
        for (BlueprintLanguagePlugin.AccessRule rule : rules) {
            if (rule.httpMethod() != null && !rule.httpMethod().equalsIgnoreCase(s.httpMethod())) {
                continue;
            }
            for (String pattern : rule.patterns()) {
                if (pathMatches(pattern, s.route())) {
                    String text = rule.rule() + " — regola per percorso " + (rule.httpMethod() == null ? "" : rule.httpMethod() + " ")
                            + pattern + " (" + rule.source() + ")";
                    return new Component(endpoint.key(), endpoint.kind(), endpoint.name(), endpoint.filePath(),
                            endpoint.language(), endpoint.description(), endpoint.pseudocode(),
                            s.toBuilder().authorization(text).build(), endpoint.startLine(), endpoint.endLine());
                }
            }
        }
        return endpoint;
    }

    /**
     * A Spring path pattern against a route of the code: {@code *} is one segment,
     * {@code **} any number of them, {@code {x}} one segment; a variable of the route
     * ({@code {id}}) matches only a wildcard or a variable of the pattern.
     */
    public static boolean pathMatches(String pattern, String route) {
        return matchSegments(segments(pattern), 0, segments(route), 0);
    }

    private static List<String> segments(String path) {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return java.util.Arrays.stream(clean.split("/")).filter(part -> !part.isEmpty()).toList();
    }

    private static boolean matchSegments(List<String> pattern, int p, List<String> route, int r) {
        if (p == pattern.size()) {
            return r == route.size();
        }
        if (pattern.get(p).equals("**")) {
            for (int next = r; next <= route.size(); next++) {
                if (matchSegments(pattern, p + 1, route, next)) {
                    return true;
                }
            }
            return false;
        }
        return r < route.size() && segmentMatches(pattern.get(p), route.get(r))
                && matchSegments(pattern, p + 1, route, r + 1);
    }

    private static boolean segmentMatches(String pattern, String segment) {
        if (pattern.equals("*") || pattern.startsWith("{")) {
            return true;
        }
        if (segment.startsWith("{")) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(segment);
        }
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            regex.append(java.util.regex.Pattern.quote(part)).append(".*");
        }
        return segment.matches(regex.substring(0, regex.length() - 2));
    }

    private static Component merge(Component first, Component second) {
        if (first.kind() != NodeKind.DATABASE || second.kind() != NodeKind.DATABASE) {
            return first;
        }
        boolean secondCreates = second.structure().annotations().contains(SqlMigrationPlugin.CREATED)
                && !first.structure().annotations().contains(SqlMigrationPlugin.CREATED);
        Component base = secondCreates ? second : first;
        Component extra = secondCreates ? first : second;
        List<String> columns = new ArrayList<>(base.structure().columns());
        extra.structure().columns().stream().filter(c -> !columns.contains(c)).forEach(columns::add);
        List<String> notes = new ArrayList<>(base.structure().annotations());
        extra.structure().annotations().stream().filter(a -> !notes.contains(a)).forEach(notes::add);
        return new Component(base.key(), base.kind(), base.name(), base.filePath(), base.language(),
                base.description() != null ? base.description() : extra.description(), base.pseudocode(),
                base.structure().toBuilder().columns(columns).annotations(notes).build(), base.startLine(), base.endLine());
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** {@code V10__x.sql} after {@code V9__y.sql}: the version as a number, then the path. */
    static String sortKey(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.matches("V\\d+__.*\\.sql")) {
            String version = name.substring(1, name.indexOf("__"));
            return path.substring(0, path.lastIndexOf('/') + 1) + "V" + "0".repeat(Math.max(0, 8 - version.length())) + version;
        }
        return path;
    }
}
