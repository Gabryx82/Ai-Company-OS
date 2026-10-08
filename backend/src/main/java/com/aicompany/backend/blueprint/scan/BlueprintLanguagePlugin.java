package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;

import java.util.List;

/**
 * One language, read into Blueprint components (ADR-033 §5). A plugin reads one
 * file at a time and says what it contains and what it refers to; resolving the
 * references across files, reconciling with what the operator wrote and laying
 * the blocks out is common work, done once for every language.
 *
 * <p>A plugin never runs or compiles anything, and declares how faithful it is:
 * {@code ast} when it parses the language, {@code lexical} when it reads it with
 * patterns. Adding a language is adding a class that implements this interface
 * and listing it in {@link BlueprintScanner#DEFAULT_PLUGINS}.
 */
public interface BlueprintLanguagePlugin {

    /** The language this plugin reads, as blocks report it: {@code java}, {@code typescript}… */
    String language();

    /** {@code ast} or {@code lexical}. */
    String fidelity();

    boolean supports(String relativePath);

    FileAnalysis analyze(SourceFile file);

    /** A file of the project: its path relative to the project folder, with forward slashes, and its text. */
    record SourceFile(String path, String text) {
    }

    /**
     * What one file contains, and what it points at; and, for a security
     * configuration, the access rules it declares by path (PHASE 32, TD-62).
     */
    record FileAnalysis(List<Component> components, List<Reference> references, List<AccessRule> accessRules) {
        public FileAnalysis(List<Component> components, List<Reference> references) {
            this(components, references, List.of());
        }

        public static FileAnalysis empty() {
            return new FileAnalysis(List.of(), List.of());
        }
    }

    /**
     * One rule of a security configuration that decides by path (PHASE 32, TD-62):
     * {@code requestMatchers(HttpMethod.DELETE, "/api/tasks/*").hasRole("ADMIN")}.
     * {@code httpMethod} is null when the rule matches every method; the rules of a
     * file are listed in the order they are declared, because the first that
     * matches decides. {@code source} says where it is: {@code File.java:139}.
     */
    record AccessRule(String httpMethod, List<String> patterns, String rule, String source) {
    }

    /**
     * A component as a plugin read it. {@code key} is stable across scans -- it is
     * how the reconciliation recognises a block it has seen before (ADR-033 §4).
     */
    record Component(String key, NodeKind kind, String name, String filePath, String language, String description,
                     String pseudocode, CodeStructure structure, Integer startLine, Integer endLine) {
    }

    /**
     * A reference from a component to something else, not yet resolved: the target
     * is a list of candidate keys, tried in order -- a Java simple name can be an
     * explicit import, the same package, or a wildcard import, and only the set of
     * all components can say which exists. A candidate starting with
     * {@code route:} is a URL a test calls, matched against the endpoints.
     *
     * <p>{@code internal} says the target is expected to be part of the project:
     * a reference like that which resolves to nothing is counted as unresolved,
     * while one to {@code String} or to a library simply is not a block.
     */
    record Reference(String sourceKey, List<String> targetCandidates, RelationType relation, String label,
                     String codePath, Integer line, String excerpt, boolean internal) {
    }
}
