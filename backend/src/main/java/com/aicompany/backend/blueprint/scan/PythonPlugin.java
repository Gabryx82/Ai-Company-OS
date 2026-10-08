package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python, read lexically (ADR-033 §5): one block per module, plus one block per
 * FastAPI/Flask-style route. Its classes, functions and imports are its
 * structure; imports resolved against the module's own ancestors are its
 * relations. PHASE 32 (TD-57): when a Python interpreter is on this machine the
 * modules are parsed by its {@code ast} ({@code ast}), and only otherwise read with
 * patterns ({@code lexical}).
 */
public final class PythonPlugin implements BlueprintLanguagePlugin {

    private static final Pattern CLASS = Pattern.compile("^class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*:", Pattern.MULTILINE);
    private static final Pattern DEF = Pattern.compile("^(?:async\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
    private static final Pattern FROM = Pattern.compile("^\\s*from\\s+(\\.*[\\w.]*)\\s+import\\s+([^\\n]+)", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+(?:\\s*,\\s*[\\w.]+)*)", Pattern.MULTILINE);
    private static final Pattern ROUTE = Pattern.compile(
            "^@(\\w+)\\.(get|post|put|delete|patch|route)\\(\\s*[\"']([^\"']*)[\"'][^\\n]*\\n(?:@[^\\n]*\\n)*(?:async\\s+)?def\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern PREFIX = Pattern.compile("(\\w+)\\s*=\\s*APIRouter\\([^)]*prefix\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern DOCSTRING = Pattern.compile("\\A(?:\\s*#[^\\n]*\\n)*\\s*(?:\"\"\"|''')\\s*([^\\n]+)");
    private static final Pattern CONTROL = Pattern.compile(
            "^(\\s+)(if .*|elif .*|else:|for .*|while .*|return\\b.*|raise\\b.*|try:|except.*|with .*|await .*)$");

    @Override
    public String language() {
        return "python";
    }

    @Override
    public String fidelity() {
        return "lexical";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.endsWith(".py");
    }

    public static String keyOf(String path) {
        return "py:" + path;
    }

    @Override
    public FileAnalysis analyze(SourceFile file) {
        return analyze(file, null);
    }

    /**
     * PHASE 32 (TD-57): every Python file of the project parsed at once by Python's own
     * {@code ast}. Null when no interpreter answers: the files are then read lexically.
     */
    @Override
    public Object prepare(List<SourceFile> files) {
        return ExternalAst.run(List.of(ExternalAst.executable("AICOS_PYTHON", "python")), "py-ast.py", Map.of(), files)
                .orElse(null);
    }

    @Override
    public String fidelity(Object prepared) {
        return prepared instanceof ExternalAst.Facts ? "ast" : fidelity();
    }

    /** A FastAPI/Flask route: its decorator's line, its function's. */
    record Route(String method, String path, String handler, int line, int end) {
    }

    /** {@code from module import names}. */
    record FromImport(String module, List<String> names, int line, String text) {
    }

    /** {@code import a, b}. */
    record PlainImport(List<String> modules, int line, String text) {
    }

    /** What a module declares and imports -- from the parser, or from the patterns. */
    record Facts(Set<String> classes, Set<String> bases, List<String> functions, String prefix, List<Route> routes,
                 List<FromImport> froms, List<PlainImport> plains) {
    }

    @Override
    public FileAnalysis analyze(SourceFile file, Object prepared) {
        String path = file.path();
        String text = file.text();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String key = keyOf(path);
        List<Component> components = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        JsonNode parsed = prepared instanceof ExternalAst.Facts facts ? facts.files().get(path) : null;
        Facts f = parsed != null ? fromAst(parsed) : lexical(text);
        Set<String> classes = f.classes();
        Set<String> bases = f.bases();
        List<String> functions = f.functions();

        List<String> routes = new ArrayList<>();
        for (Route r : f.routes()) {
            String route = JavaAstPlugin.normalizeRoute(f.prefix() + "/" + r.path());
            String endpointKey = "endpoint:" + path + "#" + r.handler() + ":" + r.method() + " " + route;
            routes.add(r.method() + " " + route + " → " + r.handler() + "()");
            components.add(new Component(endpointKey, NodeKind.ENDPOINT, r.method() + " " + route, path, "python",
                    "Gestito da " + r.handler() + "() in " + fileName + ".", "", CodeStructure.builder().httpMethod(r.method())
                    .route(route).controller(fileName).signature(r.handler() + "(…)").authorization("—").build(),
                    r.line(), r.end()));
            references.add(new Reference(endpointKey, List.of(key), RelationType.BELONGS_TO, r.handler() + "()", path,
                    r.line(), null, false));
        }

        String lower = path.toLowerCase(Locale.ROOT);
        NodeKind kind;
        if (fileName.startsWith("test_") || fileName.endsWith("_test.py") || lower.contains("/tests/") || fileName.equals("conftest.py")) {
            kind = NodeKind.TEST;
        } else if (!routes.isEmpty()) {
            kind = NodeKind.CONTROLLER;
        } else if (!bases.isEmpty() && bases.stream().allMatch(b -> b.endsWith("BaseModel") || b.endsWith("TypedDict") || b.equals("Enum") || b.endsWith("BaseSettings"))) {
            kind = fileName.contains("config") || fileName.contains("settings") ? NodeKind.CONFIGURATION : NodeKind.DTO;
        } else if (fileName.contains("config") || fileName.contains("settings")) {
            kind = NodeKind.CONFIGURATION;
        } else if (text.contains("__name__ == \"__main__\"") || text.contains("__name__ == '__main__'")) {
            kind = NodeKind.SCRIPT;
        } else if (!classes.isEmpty()) {
            kind = NodeKind.CLASS;
        } else if (!functions.isEmpty()) {
            kind = NodeKind.FUNCTION;
        } else {
            kind = NodeKind.FILE;
        }

        // --- imports, resolved against the module's own ancestors
        List<String> imports = new ArrayList<>();
        for (FromImport from : f.froms()) {
            String module = from.module();
            imports.add(module);
            List<String> candidates = new ArrayList<>(moduleCandidates(path, module));
            if (module.matches("\\.+")) {
                // from . import util -> the sibling module util.py
                for (String n : from.names()) {
                    candidates.addAll(moduleCandidates(path, module + n));
                }
            }
            if (!candidates.isEmpty()) {
                references.add(new Reference(key, candidates, RelationType.IMPORTS, module, path, from.line(), from.text(),
                        module.startsWith(".")));
            }
        }
        for (PlainImport plain : f.plains()) {
            for (String mod : plain.modules()) {
                imports.add(mod);
                List<String> candidates = moduleCandidates(path, mod);
                if (!candidates.isEmpty()) {
                    references.add(new Reference(key, candidates, RelationType.IMPORTS, mod, path, plain.line(), plain.text(), false));
                }
            }
        }

        String name = kind == NodeKind.CLASS && classes.size() == 1 ? classes.iterator().next() : fileName;
        Matcher doc = DOCSTRING.matcher(text);
        String description = doc.find() ? doc.group(1).replace("\"\"\"", "").strip() : switch (kind) {
            case CONTROLLER -> "Modulo con " + routes.size() + " route HTTP.";
            case TEST -> "Test automatico.";
            case DTO -> "Modelli di dati: " + String.join(", ", classes.stream().limit(6).toList()) + ".";
            case CLASS -> "Classi: " + String.join(", ", classes.stream().limit(6).toList()) + ".";
            default -> "Modulo Python " + fileName + ".";
        };
        CodeStructure structure = CodeStructure.builder()
                .className(classes.isEmpty() ? null : String.join(", ", classes))
                .extendsType(bases.isEmpty() ? null : String.join(", ", bases))
                .exports(functions).imports(imports).build();
        components.add(0, new Component(key, kind, name, path, "python", description,
                routes.isEmpty() ? pseudocode(text) : String.join("\n", routes.stream().map(r -> "ON " + r).toList()),
                structure, 1, (int) text.lines().count()));
        return new FileAnalysis(components, references);
    }

    /** PHASE 32 (TD-57): the facts Python's parser found. */
    static Facts fromAst(JsonNode n) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> bases = new LinkedHashSet<>();
        for (JsonNode c : n.path("classes")) {
            classes.add(c.path("name").asString());
            c.path("bases").forEach(b -> bases.add(b.asString()));
        }
        List<String> functions = new ArrayList<>();
        n.path("functions").forEach(fn -> functions.add(fn.asString()));
        List<Route> routes = new ArrayList<>();
        for (JsonNode r : n.path("routes")) {
            routes.add(new Route(r.path("method").asString(), r.path("path").asString(), r.path("handler").asString(),
                    r.path("line").asInt(1), r.path("end").asInt(1)));
        }
        List<FromImport> froms = new ArrayList<>();
        List<PlainImport> plains = new ArrayList<>();
        for (JsonNode i : n.path("imports")) {
            if (i.has("from")) {
                List<String> names = new ArrayList<>();
                i.path("names").forEach(x -> names.add(x.asString()));
                froms.add(new FromImport(i.path("from").asString(), names, i.path("line").asInt(1), i.path("text").asString("")));
            } else {
                List<String> modules = new ArrayList<>();
                i.path("modules").forEach(x -> modules.add(x.asString()));
                plains.add(new PlainImport(modules, i.path("line").asInt(1), i.path("text").asString("")));
            }
        }
        return new Facts(classes, bases, functions, n.path("prefix").asString(""), routes, froms, plains);
    }

    /** The facts read with patterns. */
    static Facts lexical(String text) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> bases = new LinkedHashSet<>();
        Matcher m = CLASS.matcher(text);
        while (m.find()) {
            classes.add(m.group(1));
            if (m.group(2) != null) {
                for (String b : m.group(2).split(",")) {
                    if (!b.isBlank() && !b.contains("=")) bases.add(b.strip());
                }
            }
        }
        List<String> functions = new ArrayList<>();
        m = DEF.matcher(text);
        while (m.find()) functions.add(m.group(1) + "(" + m.group(2).replaceAll("\\s+", " ").strip() + ")");
        String prefix = "";
        Matcher pm = PREFIX.matcher(text);
        if (pm.find()) prefix = pm.group(2);
        List<Route> routes = new ArrayList<>();
        m = ROUTE.matcher(text);
        while (m.find()) {
            String method = m.group(2).equals("route") ? "GET" : m.group(2).toUpperCase(Locale.ROOT);
            routes.add(new Route(method, m.group(3), m.group(4), TypeScriptPlugin.lineOf(text, m.start()),
                    TypeScriptPlugin.lineOf(text, m.end())));
        }
        List<FromImport> froms = new ArrayList<>();
        m = FROM.matcher(text);
        while (m.find()) {
            List<String> names = new ArrayList<>();
            for (String name : m.group(2).split(",")) {
                names.add(name.strip().split("\\s+")[0]);
            }
            froms.add(new FromImport(m.group(1), names, TypeScriptPlugin.lineOf(text, m.start()), m.group().strip()));
        }
        List<PlainImport> plains = new ArrayList<>();
        m = IMPORT.matcher(text);
        while (m.find()) {
            List<String> modules = new ArrayList<>();
            for (String module : m.group(1).split(",")) {
                modules.add(module.strip());
            }
            plains.add(new PlainImport(modules, TypeScriptPlugin.lineOf(text, m.start()), m.group().strip()));
        }
        return new Facts(classes, bases, functions, prefix, routes, froms, plains);
    }

    /**
     * Where a module can be: relative to the file for {@code .x}, else rooted at
     * each ancestor of the file -- {@code app.models} from {@code engine/app/main.py}
     * is {@code engine/app/models.py}, the way the interpreter finds it when run
     * from {@code engine/}.
     */
    static List<String> moduleCandidates(String from, String module) {
        List<String> out = new ArrayList<>();
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
        if (module.startsWith(".")) {
            int dots = module.length() - module.replaceFirst("^\\.+", "").length();
            String base = dir;
            for (int i = 1; i < dots && base.contains("/"); i++) {
                base = base.substring(0, base.lastIndexOf('/'));
            }
            String rest = module.substring(dots).replace('.', '/');
            if (rest.isEmpty()) {
                return out;
            }
            String p = (base.isEmpty() ? "" : base + "/") + rest;
            out.add(keyOf(p + ".py"));
            out.add(keyOf(p + "/__init__.py"));
            return out;
        }
        String rest = module.replace('.', '/');
        String ancestor = dir;
        while (true) {
            String p = (ancestor.isEmpty() ? "" : ancestor + "/") + rest;
            out.add(keyOf(p + ".py"));
            out.add(keyOf(p + "/__init__.py"));
            if (ancestor.isEmpty()) break;
            ancestor = ancestor.contains("/") ? ancestor.substring(0, ancestor.lastIndexOf('/')) : "";
        }
        return out;
    }

    static String pseudocode(String text) {
        List<String> out = new ArrayList<>();
        String current = null;
        for (String line : text.lines().toList()) {
            Matcher d = DEF.matcher(line);
            if (d.find() && d.start() == 0) {
                current = d.group(1);
                out.add(current + "(" + d.group(2).replaceAll("\\s+", " ").strip() + "):");
                continue;
            }
            Matcher c = CONTROL.matcher(line);
            if (current != null && c.matches()) {
                int indent = Math.min(c.group(1).length() / 4, 6);
                String l = c.group(2).replaceFirst(":$", "").replaceFirst("^if ", "IF ").replaceFirst("^elif ", "ELSE IF ")
                        .replaceFirst("^else$", "ELSE").replaceFirst("^for ", "FOR EACH ").replaceFirst("^return\\b", "RETURN")
                        .replaceFirst("^raise\\b", "REJECT").replaceFirst("^await ", "AWAIT ");
                out.add("  ".repeat(Math.max(indent, 1)) + (l.length() > 80 ? l.substring(0, 79) + "…" : l));
            }
            if (out.size() >= 40) {
                out.add("…");
                break;
            }
        }
        return String.join("\n", out);
    }
}
