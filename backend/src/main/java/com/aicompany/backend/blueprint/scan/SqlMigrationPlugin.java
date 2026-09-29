package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL migrations, read lexically (ADR-033 §5): a {@code CREATE TABLE} is a
 * DATABASE block keyed by the table's name, so the JPA entity that names it
 * ({@code @Table}) gets its ENTITY_TO_TABLE edge; an {@code ALTER TABLE … ADD
 * COLUMN} in a later migration adds to the same block (the scanner merges them);
 * a {@code REFERENCES} is a BELONGS_TO edge between tables.
 */
public final class SqlMigrationPlugin implements BlueprintLanguagePlugin {

    /** Marks, in the structure's annotations, whether a block comes from a CREATE or an ALTER. */
    public static final String CREATED = "CREATE TABLE";
    public static final String ALTERED = "ALTER TABLE";

    private static final Pattern CREATE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"]+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_ADD = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\"]+)\\s+ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCES = Pattern.compile("REFERENCES\\s+([\\w.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_COLUMNS = Set.of("constraint", "primary", "unique", "foreign", "check", "exclude", "like");

    @Override
    public String language() {
        return "sql";
    }

    @Override
    public String fidelity() {
        return "lexical";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".sql");
    }

    public static String keyOf(String table) {
        return "table:" + table.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public FileAnalysis analyze(SourceFile file) {
        String text = file.text().replaceAll("--[^\\n]*", "");
        List<Component> components = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        String migration = file.path().substring(file.path().lastIndexOf('/') + 1);
        Matcher m = CREATE.matcher(text);
        while (m.find()) {
            String table = m.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
            String body = balanced(text, m.end() - 1);
            List<String> columns = new ArrayList<>();
            for (String part : splitTopLevel(body)) {
                String first = part.strip().split("\\s+")[0].replace("\"", "");
                if (!first.isEmpty() && !NOT_COLUMNS.contains(first.toLowerCase(Locale.ROOT))) {
                    columns.add(first);
                }
                Matcher r = REFERENCES.matcher(part);
                if (r.find()) {
                    String target = r.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
                    if (!target.equals(table)) {
                        references.add(new Reference(keyOf(table), List.of(keyOf(target)), RelationType.BELONGS_TO, first,
                                file.path(), TypeScriptPlugin.lineOf(file.text(), m.start()), part.strip(), true));
                    }
                }
            }
            int line = TypeScriptPlugin.lineOf(file.text(), m.start());
            components.add(new Component(keyOf(table), NodeKind.DATABASE, table, file.path(), "sql",
                    "Tabella " + table + ", creata da " + migration + ".", "",
                    CodeStructure.builder().table(table).columns(columns).annotations(List.of(CREATED)).build(),
                    line, line + body.split("\n").length));
        }
        Matcher a = ALTER_ADD.matcher(text);
        while (a.find()) {
            String table = a.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
            String column = a.group(2).replace("\"", "");
            if (NOT_COLUMNS.contains(column.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int line = TypeScriptPlugin.lineOf(file.text(), a.start());
            components.add(new Component(keyOf(table), NodeKind.DATABASE, table, file.path(), "sql", null, "",
                    CodeStructure.builder().table(table).columns(List.of(column)).annotations(List.of(ALTERED + " " + migration)).build(),
                    line, line));
            int end = text.indexOf(';', a.end());
            Matcher r = REFERENCES.matcher(text.substring(a.end(), end < 0 ? text.length() : end));
            if (r.find()) {
                String target = r.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
                if (!target.equals(table)) {
                    references.add(new Reference(keyOf(table), List.of(keyOf(target)), RelationType.BELONGS_TO, column,
                            file.path(), line, null, true));
                }
            }
        }
        return new FileAnalysis(components, references);
    }

    /** The text between the parenthesis at {@code open} and its match. */
    static String balanced(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) {
                return text.substring(open + 1, i);
            }
        }
        return text.substring(open + 1);
    }

    /** Splits a column list on commas that are not inside parentheses. */
    static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }
}
