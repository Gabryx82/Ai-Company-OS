package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript and JavaScript, read lexically (ADR-033 §5): one block per file,
 * whose kind is what the file mostly is -- a React component, a hook, a store, a
 * test, a configuration, a class, a set of types or functions. What the file
 * exports, imports, which hooks it uses and which props its component takes are
 * its structure; its relative imports and the {@code /api/...} routes it calls are
 * its relations. An AST plugin for these languages can replace this one behind
 * the same interface.
 */
public final class TypeScriptPlugin implements BlueprintLanguagePlugin {

    private static final Pattern IMPORT = Pattern.compile(
            "(?:import\\s+(?:type\\s+)?([^'\"]*?)\\s*from\\s*|export\\s+[^'\"]*?\\s+from\\s+|require\\(\\s*|import\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern EXPORT_FUNCTION = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(\\w+)", Pattern.MULTILINE);
    private static final Pattern EXPORT_CONST = Pattern.compile("^export\\s+(?:const|let|var)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern EXPORT_CLASS = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+([\\w.]+))?", Pattern.MULTILINE);
    private static final Pattern EXPORT_TYPE = Pattern.compile(
            "^export\\s+(?:declare\\s+)?(interface|type|enum)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern FUNCTION_DECL = Pattern.compile(
            "^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([A-Z]\\w*)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern CONST_ARROW = Pattern.compile(
            "^(?:export\\s+)?const\\s+([A-Z]\\w*)\\s*(?::[^=]+)?=\\s*(?:memo\\()?\\(?", Pattern.MULTILINE);
    private static final Pattern HOOK_USE = Pattern.compile("\\b(use[A-Z]\\w*)\\s*\\(");
    private static final Pattern PROPS = Pattern.compile("function\\s+%s\\s*\\(\\s*\\{([^}]*)}");
    private static final Pattern API_CALL = Pattern.compile(
            "(?:[\"'](GET|POST|PUT|DELETE|PATCH)[\"']\\s*,\\s*)?[`\"'](/api/[^`\"'\\s?]*)");
    private static final Pattern DOC = Pattern.compile("^\\s*/\\*\\*\\s*\\n?\\s*\\*?\\s*([^\\n*][^\\n]*)", Pattern.MULTILINE);
    private static final Pattern LINE_COMMENT_HEAD = Pattern.compile("\\A(?:\\s*//[^\\n]*\\n)+");
    private static final Pattern CONTROL = Pattern.compile(
            "^\\s*(if\\s*\\(.*|else.*|for\\s*\\(.*|while\\s*\\(.*|return\\b.*|throw\\b.*|try\\b.*|catch\\b.*|switch\\s*\\(.*|await\\s+[\\w.]+\\(.*)$");

    @Override
    public String language() {
        return "typescript";
    }

    @Override
    public String fidelity() {
        return "lexical";
    }

    @Override
    public boolean supports(String relativePath) {
        String p = relativePath.toLowerCase(Locale.ROOT);
        return (p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs"))
                && !p.endsWith(".d.ts") && !p.endsWith(".min.js");
    }

    public static String keyOf(String path) {
        return "ts:" + path;
    }

    @Override
    public FileAnalysis analyze(SourceFile file) {
        String path = file.path();
        String text = file.text();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String base = fileName.replaceAll("\\.(test|spec)?\\.?[mc]?[jt]sx?$", "").replaceAll("\\.(test|spec)$", "");
        String language = path.endsWith(".ts") || path.endsWith(".tsx") ? "typescript" : "javascript";
        boolean jsx = path.endsWith(".tsx") || path.endsWith(".jsx");
        String key = keyOf(path);

        Set<String> exports = new LinkedHashSet<>();
        Set<String> functions = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        String extendsType = null;
        Matcher m = EXPORT_FUNCTION.matcher(text);
        while (m.find()) { exports.add(m.group(1)); functions.add(m.group(1)); }
        m = EXPORT_CONST.matcher(text);
        while (m.find()) { exports.add(m.group(1)); }
        m = EXPORT_CLASS.matcher(text);
        while (m.find()) {
            exports.add(m.group(1));
            classes.add(m.group(1));
            if (extendsType == null) extendsType = m.group(2);
        }
        m = EXPORT_TYPE.matcher(text);
        while (m.find()) { exports.add(m.group(2)); types.add(m.group(2)); }

        Set<String> components = new LinkedHashSet<>();
        if (jsx) {
            m = FUNCTION_DECL.matcher(text);
            while (m.find()) components.add(m.group(1));
            m = CONST_ARROW.matcher(text);
            while (m.find()) components.add(m.group(1));
        }
        Set<String> hooksDefined = new LinkedHashSet<>();
        for (String f : exports) {
            if (f.matches("use[A-Z]\\w*")) hooksDefined.add(f);
        }
        Set<String> hooksUsed = new LinkedHashSet<>();
        m = HOOK_USE.matcher(text);
        while (m.find()) {
            if (!hooksDefined.contains(m.group(1))) hooksUsed.add(m.group(1));
        }

        String lower = path.toLowerCase(Locale.ROOT);
        NodeKind kind;
        if (lower.matches(".*\\.(test|spec)\\.[mc]?[jt]sx?$") || lower.contains("/__tests__/")) {
            kind = NodeKind.TEST;
        } else if (fileName.matches("(?i).*\\.config\\.[mc]?[jt]s$") || fileName.matches("(?i)(vite|vitest|webpack|jest|eslint|tailwind|postcss)\\..*")) {
            kind = NodeKind.CONFIGURATION;
        } else if (text.matches("(?s).*\\b(createStore|configureStore|createSlice|defineStore)\\b.*")
                || text.matches("(?s).*\\bcreate\\s*(<[^>]*>)?\\s*\\(\\s*\\(?\\s*set\\b.*") || base.toLowerCase(Locale.ROOT).endsWith("store")) {
            kind = NodeKind.STORE;
        } else if (!hooksDefined.isEmpty() && (base.startsWith("use") || components.isEmpty())) {
            kind = NodeKind.HOOK;
        } else if (!components.isEmpty()) {
            kind = NodeKind.COMPONENT;
        } else if (!classes.isEmpty()) {
            kind = NodeKind.CLASS;
        } else if (!types.isEmpty() && functions.isEmpty() && exports.size() == types.size()) {
            kind = NodeKind.DTO;
        } else if (text.contains("process.argv") || text.startsWith("#!")) {
            kind = NodeKind.SCRIPT;
        } else if (!functions.isEmpty()) {
            kind = NodeKind.FUNCTION;
        } else {
            kind = NodeKind.FILE;
        }

        String name = switch (kind) {
            case COMPONENT -> components.contains(base) ? base
                    : components.stream().filter(exports::contains).findFirst().orElse(components.iterator().next());
            case HOOK -> hooksDefined.contains(base) ? base : hooksDefined.iterator().next();
            case CLASS -> classes.contains(base) ? base : classes.iterator().next();
            default -> fileName;
        };

        List<String> props = new ArrayList<>();
        if (kind == NodeKind.COMPONENT) {
            Matcher pm = Pattern.compile(PROPS.pattern().formatted(Pattern.quote(name))).matcher(text);
            if (pm.find()) {
                for (String part : pm.group(1).split(",")) {
                    String prop = part.strip().split("[:=\\s]")[0];
                    if (!prop.isEmpty() && prop.matches("[A-Za-z_$][\\w$]*")) props.add(prop);
                }
            }
        }

        // --- imports and relations
        List<String> imports = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        m = IMPORT.matcher(text);
        while (m.find()) {
            String spec = m.group(2);
            imports.add(spec);
            if (!spec.startsWith(".")) {
                continue;
            }
            List<String> candidates = relativeCandidates(path, spec);
            String what = m.group(1) == null ? "" : m.group(1).replaceAll("\\s+", " ").strip();
            // A type-only import may name a declaration file (.d.ts), which is not a block.
            boolean typeOnly = m.group().startsWith("import type");
            references.add(new Reference(key, candidates, RelationType.IMPORTS, what.isEmpty() ? spec : what, path,
                    lineOf(text, m.start()), m.group().strip(), !typeOnly));
        }
        m = API_CALL.matcher(text);
        int calls = 0;
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && calls < 200) {
            String method = m.group(1) == null ? "*" : m.group(1);
            String route = m.group(2).replaceAll("\\$\\{[^}]*}", "{}");
            if (seen.add(method + " " + route)) {
                calls++;
                references.add(new Reference(key, List.of("route:" + method + " " + route), RelationType.CALLS,
                        (method.equals("*") ? "" : method + " ") + route, path, lineOf(text, m.start()),
                        lineText(text, m.start()), false));
            }
        }

        CodeStructure structure = CodeStructure.builder()
                .className(classes.isEmpty() ? null : name).extendsType(extendsType)
                .exports(List.copyOf(exports)).imports(imports).props(props).hooks(List.copyOf(hooksUsed))
                .build();
        String description = description(text).orElse(switch (kind) {
            case COMPONENT -> "Componente React " + name + (hooksUsed.isEmpty() ? "." : "; usa " + String.join(", ", hooksUsed.stream().limit(5).toList()) + ".");
            case HOOK -> "Hook React " + name + ".";
            case STORE -> "Stato condiviso dell'interfaccia.";
            case TEST -> "Test automatico.";
            case CONFIGURATION -> "Configurazione degli strumenti.";
            case CLASS -> "Classe " + name + ".";
            case DTO -> "Tipi: " + String.join(", ", types.stream().limit(6).toList()) + (types.size() > 6 ? "…" : ".");
            default -> exports.isEmpty() ? "Modulo " + fileName + "." : "Esporta " + String.join(", ", exports.stream().limit(6).toList()) + ".";
        });
        int lines = (int) text.lines().count();
        return new FileAnalysis(List.of(new Component(key, kind, name, path, language, description, pseudocode(text),
                structure, 1, lines)), references);
    }

    /** Where a relative specifier can land, in the order a bundler tries: as written, then with extensions, then index. */
    static List<String> relativeCandidates(String from, String spec) {
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
        String candidate = Path.of(dir.isEmpty() ? "." : dir).resolve(spec).normalize().toString().replace('\\', '/');
        if (candidate.startsWith("./")) {
            candidate = candidate.substring(2);
        }
        String stripped = candidate.replaceAll("\\.(js|jsx|mjs)$", "");
        List<String> out = new ArrayList<>();
        for (String option : List.of(candidate, stripped + ".ts", stripped + ".tsx", stripped + ".js", stripped + ".jsx",
                stripped + ".mjs", stripped + "/index.ts", stripped + "/index.tsx", stripped + "/index.js")) {
            out.add(keyOf(option));
        }
        return out;
    }

    private static java.util.Optional<String> description(String text) {
        Matcher head = LINE_COMMENT_HEAD.matcher(text);
        if (head.find()) {
            String first = head.group().lines().map(l -> l.replaceFirst("^\\s*//\\s?", "").strip())
                    .filter(l -> !l.isEmpty()).findFirst().orElse("");
            if (!first.isEmpty()) return java.util.Optional.of(cut(first));
        }
        Matcher doc = DOC.matcher(text);
        if (doc.find() && doc.start() < 400) {
            return java.util.Optional.of(cut(doc.group(1).strip()));
        }
        return java.util.Optional.empty();
    }

    private static String cut(String s) {
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }

    /** The control flow of the file, lexically: good enough to read, not to trust as an AST. */
    static String pseudocode(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.lines().toList()) {
            Matcher c = CONTROL.matcher(line);
            if (c.matches()) {
                String l = c.group(1).strip().replaceAll("\\s*\\{\\s*$", "");
                l = l.replaceFirst("^if\\s*\\((.*)\\)$", "IF $1").replaceFirst("^return\\b", "RETURN")
                        .replaceFirst("^throw\\b", "REJECT").replaceFirst("^for\\s*\\((.*)\\)$", "FOR EACH $1")
                        .replaceFirst("^await\\s+", "AWAIT ");
                int indent = (line.length() - line.stripLeading().length()) / 2;
                out.add("  ".repeat(Math.min(indent, 6)) + (l.length() > 80 ? l.substring(0, 79) + "…" : l));
                if (out.size() >= 30) {
                    out.add("…");
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            return "";
        }
        int min = out.stream().filter(l -> !l.equals("…")).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        return String.join("\n", out.stream().map(l -> l.length() >= min ? l.substring(Math.min(min, l.length() - l.stripLeading().length())) : l).toList());
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    static String lineText(String text, int offset) {
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        String l = text.substring(start, end < 0 ? text.length() : end).strip();
        return l.length() > 200 ? l.substring(0, 199) + "…" : l;
    }
}
