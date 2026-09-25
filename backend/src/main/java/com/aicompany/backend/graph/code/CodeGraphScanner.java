package com.aicompany.backend.graph.code;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code graph of a project folder (ADR-030): its source files, the imports
 * between them, the external packages they use, and the import cycles.
 *
 * <p>Static and lexical on purpose: it reads import statements with regular
 * expressions, the way a person skimming the code would, and never runs or
 * compiles anything. TypeScript/JavaScript, Java and Python; manifests
 * ({@code package.json}, {@code pom.xml}, {@code requirements.txt}) add the
 * declared dependencies. Bounded: at most {@link #MAX_FILES} files of at most
 * {@link #MAX_BYTES} each, generated and vendored folders skipped.
 */
public final class CodeGraphScanner {

    public static final int MAX_FILES = 4000;
    public static final long MAX_BYTES = 512 * 1024;

    static final Set<String> SKIPPED = Set.of("node_modules", ".git", "target", "dist", "build", "out", ".venv",
            "venv", "__pycache__", ".idea", ".vscode", ".gradle", ".next", "coverage", ".aicos", ".mvn");

    private static final Pattern JS_IMPORT = Pattern.compile(
            "(?:import\\s+(?:[^'\"]*?\\s+from\\s+)?|export\\s+[^'\"]*?\\s+from\\s+|require\\(\\s*|import\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+(\\.*[\\w.]*)\\s+import\\s", Pattern.MULTILINE);
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+(?:\\s*,\\s*[\\w.]+)*)", Pattern.MULTILINE);
    private static final Pattern POM_DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>", Pattern.DOTALL);

    public record Node(String id, String label, String kind, String language, String directory, int lines) {
    }

    public record Edge(String source, String target, String kind) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges, List<List<String>> cycles, Map<String, Integer> languages,
                        int files, boolean truncated) {
    }

    private CodeGraphScanner() {
    }

    public static Graph scan(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        List<Path> manifests = new ArrayList<>();
        boolean[] truncated = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && (SKIPPED.contains(dir.getFileName().toString()) || attrs.isSymbolicLink())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.size() > MAX_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString();
                if (name.equals("package.json") || name.equals("pom.xml") || name.equals("requirements.txt")) {
                    manifests.add(file);
                } else if (language(name).isPresent()) {
                    if (sources.size() >= MAX_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, String> texts = new HashMap<>();
        Map<String, String> javaClassToFile = new HashMap<>();
        Map<String, String> pythonModuleToFile = new HashMap<>();
        Map<String, Integer> languages = new TreeMap<>();
        for (Path file : sources) {
            String rel = relative(root, file);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String lang = language(file.getFileName().toString()).orElseThrow();
            texts.put(rel, text);
            languages.merge(lang, 1, Integer::sum);
            String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : ".";
            nodes.put(rel, new Node(rel, file.getFileName().toString(), "FILE", lang, dir, (int) text.lines().count()));
            if (lang.equals("java")) {
                Matcher m = JAVA_PACKAGE.matcher(text);
                String pkg = m.find() ? m.group(1) + "." : "";
                String cls = file.getFileName().toString().replaceAll("\\.java$", "");
                javaClassToFile.put(pkg + cls, rel);
            }
            if (lang.equals("python")) {
                String module = rel.replaceAll("\\.py$", "").replace('/', '.').replaceAll("\\.__init__$", "");
                pythonModuleToFile.put(module, rel);
                // also without a leading source folder (src.app.x -> app.x)
                if (module.contains(".")) {
                    pythonModuleToFile.putIfAbsent(module.substring(module.indexOf('.') + 1), rel);
                }
            }
        }

        Set<Edge> edges = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            String rel = entry.getKey();
            String text = entry.getValue();
            String lang = nodes.get(rel).language();
            switch (lang) {
                case "typescript", "javascript" -> {
                    Matcher m = JS_IMPORT.matcher(text);
                    while (m.find()) {
                        String spec = m.group(1);
                        if (spec.startsWith(".")) {
                            resolveRelative(rel, spec, nodes.keySet()).ifPresent(t -> edges.add(new Edge(rel, t, "IMPORT")));
                        } else {
                            edges.add(new Edge(rel, external(nodes, packageName(spec), "npm"), "USES"));
                        }
                    }
                }
                case "java" -> {
                    Matcher m = JAVA_IMPORT.matcher(text);
                    while (m.find()) {
                        String imported = m.group(1);
                        String target = javaClassToFile.get(imported);
                        if (target == null && imported.contains(".")) {
                            target = javaClassToFile.get(imported.substring(0, imported.lastIndexOf('.')));
                        }
                        if (target != null) {
                            if (!target.equals(rel)) {
                                edges.add(new Edge(rel, target, "IMPORT"));
                            }
                        } else if (!imported.startsWith("java.") && !imported.startsWith("javax.")) {
                            String[] parts = imported.split("\\.");
                            String group = parts.length >= 2 ? parts[0] + "." + parts[1] : imported;
                            edges.add(new Edge(rel, external(nodes, group, "java"), "USES"));
                        }
                    }
                }
                case "python" -> {
                    List<String> modules = new ArrayList<>();
                    Matcher from = PY_FROM.matcher(text);
                    while (from.find()) {
                        modules.add(from.group(1));
                    }
                    Matcher imp = PY_IMPORT.matcher(text);
                    while (imp.find()) {
                        for (String m : imp.group(1).split(",")) {
                            modules.add(m.strip());
                        }
                    }
                    for (String module : modules) {
                        if (module.startsWith(".")) {
                            String base = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')).replace('/', '.') : "";
                            String name = module.replaceFirst("^\\.+", "");
                            String absolute = (base.isEmpty() ? "" : base + (name.isEmpty() ? "" : ".")) + name;
                            Optional.ofNullable(pythonModuleToFile.get(absolute))
                                    .filter(t -> !t.equals(rel)).ifPresent(t -> edges.add(new Edge(rel, t, "IMPORT")));
                            continue;
                        }
                        String target = pythonModuleToFile.get(module);
                        if (target == null && module.contains(".")) {
                            target = pythonModuleToFile.get(module.substring(0, module.lastIndexOf('.')));
                        }
                        if (target != null) {
                            if (!target.equals(rel)) {
                                edges.add(new Edge(rel, target, "IMPORT"));
                            }
                        } else if (!module.isEmpty() && !PYTHON_STDLIB.contains(module.split("\\.")[0])) {
                            edges.add(new Edge(rel, external(nodes, module.split("\\.")[0], "pypi"), "USES"));
                        }
                    }
                }
                default -> {
                }
            }
        }

        for (Path manifest : manifests) {
            String rel = relative(root, manifest);
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : ".";
            nodes.put(rel, new Node(rel, manifest.getFileName().toString(), "MANIFEST", "manifest", dir, (int) text.lines().count()));
            String name = manifest.getFileName().toString();
            if (name.equals("package.json")) {
                Matcher deps = Pattern.compile("\"(dependencies|devDependencies)\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(text);
                while (deps.find()) {
                    Matcher key = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(deps.group(2));
                    while (key.find()) {
                        edges.add(new Edge(rel, external(nodes, key.group(1), "npm"), "DECLARES"));
                    }
                }
            } else if (name.equals("pom.xml")) {
                Matcher dep = POM_DEPENDENCY.matcher(text);
                while (dep.find()) {
                    edges.add(new Edge(rel, external(nodes, dep.group(1).strip(), "java"), "DECLARES"));
                }
            } else {
                for (String line : text.lines().toList()) {
                    String pkg = line.strip().split("[=<>~!\\[; ]")[0];
                    if (!pkg.isEmpty() && !pkg.startsWith("#") && !pkg.startsWith("-")) {
                        edges.add(new Edge(rel, external(nodes, pkg.toLowerCase(Locale.ROOT), "pypi"), "DECLARES"));
                    }
                }
            }
        }

        List<List<String>> cycles = cycles(nodes.keySet(), edges);
        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges), cycles, languages, sources.size(), truncated[0]);
    }

    /** Strongly connected components of the IMPORT edges with more than one file: the import cycles. */
    static List<List<String>> cycles(Set<String> ids, Set<Edge> edges) {
        Map<String, List<String>> out = new HashMap<>();
        for (Edge e : edges) {
            if (e.kind().equals("IMPORT")) {
                out.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            }
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new java.util.HashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        List<List<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String id : ids) {
            if (!index.containsKey(id)) {
                strongConnect(id, out, index, low, onStack, stack, result, counter);
            }
        }
        return result;
    }

    private static void strongConnect(String v, Map<String, List<String>> out, Map<String, Integer> index,
                                      Map<String, Integer> low, Set<String> onStack, java.util.Deque<String> stack,
                                      List<List<String>> result, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : out.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, out, index, low, onStack, stack, result, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) {
                component.sort(String::compareTo);
                result.add(component);
            }
        }
    }

    private static String external(Map<String, Node> nodes, String name, String ecosystem) {
        String id = "ext:" + ecosystem + ":" + name;
        nodes.putIfAbsent(id, new Node(id, name, "EXTERNAL", ecosystem, ecosystem, 0));
        return id;
    }

    /** {@code @scope/pkg/sub} -> {@code @scope/pkg}; {@code pkg/sub} -> {@code pkg}. */
    static String packageName(String spec) {
        String[] parts = spec.split("/");
        return spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
    }

    static Optional<String> resolveRelative(String from, String spec, Set<String> files) {
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
        Path base = Path.of(dir.isEmpty() ? "." : dir).resolve(spec).normalize();
        String candidate = base.toString().replace('\\', '/');
        if (candidate.startsWith("./")) {
            candidate = candidate.substring(2);
        }
        String stripped = candidate.replaceAll("\\.(js|jsx|mjs)$", "");
        for (String option : List.of(candidate, stripped + ".ts", stripped + ".tsx", stripped + ".js", stripped + ".jsx",
                stripped + ".mjs", stripped + "/index.ts", stripped + "/index.tsx", stripped + "/index.js")) {
            if (files.contains(option)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    static Optional<String> language(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".d.ts")) {
            return Optional.empty();
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return Optional.of("typescript");
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) {
            return Optional.of("javascript");
        }
        if (lower.endsWith(".java")) {
            return Optional.of("java");
        }
        if (lower.endsWith(".py")) {
            return Optional.of("python");
        }
        return Optional.empty();
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static final Set<String> PYTHON_STDLIB = Set.of("os", "sys", "re", "json", "typing", "pathlib", "dataclasses",
            "collections", "itertools", "functools", "datetime", "time", "math", "logging", "asyncio", "subprocess",
            "unittest", "abc", "enum", "io", "shutil", "tempfile", "uuid", "hashlib", "random", "string", "copy",
            "contextlib", "inspect", "argparse", "threading", "http", "urllib", "base64", "textwrap", "__future__");
}
