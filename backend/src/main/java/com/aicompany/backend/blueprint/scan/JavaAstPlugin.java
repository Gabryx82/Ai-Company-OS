package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.Javadoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java through an AST (ADR-033 §5): JavaParser, language level 21, parsing only --
 * no symbol solver, nothing compiled. A type is resolved by the rules a reader
 * applies: an explicit import, then the same package, then the wildcard imports;
 * the scanner then keeps the first candidate that is a block of the project.
 *
 * <p>What it reads: package, imports, top-level classes, interfaces, enums and
 * records; extends and implements; fields and constructor injection; methods with
 * signature, visibility, annotations and exceptions; the Spring stereotypes; the
 * endpoints of a controller with their route, DTOs, services and status codes;
 * calls on fields, parameters and locals of project types; {@code new} of project
 * types; the table of an entity; tests, and the endpoints and classes they cover.
 */
public final class JavaAstPlugin implements BlueprintLanguagePlugin {

    private static final Set<String> MAPPINGS = Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
            "PatchMapping", "RequestMapping");
    private static final Set<String> REPOSITORY_BASES = Set.of("JpaRepository", "CrudRepository", "Repository",
            "PagingAndSortingRepository", "ListCrudRepository", "JpaSpecificationExecutor");
    private static final Set<String> JAVA_LANG = Set.of("String", "Integer", "Long", "Short", "Byte", "Double",
            "Float", "Boolean", "Character", "Object", "Void", "Number", "Math", "System", "Thread", "Runnable",
            "Iterable", "Comparable", "Class", "Enum", "Record", "StringBuilder", "Exception", "RuntimeException",
            "Error", "Throwable", "IllegalArgumentException", "IllegalStateException", "NullPointerException",
            "UnsupportedOperationException", "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
            "var", "Process", "ProcessBuilder", "InterruptedException", "CharSequence", "AutoCloseable");
    private static final Pattern TEST_PATH = Pattern.compile("(^|/)src/test/|(^|/)test/");
    private static final Pattern API_LITERAL = Pattern.compile("^/api/[^\\s\"]*");

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setAttributeComments(true));

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String fidelity() {
        return "ast";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.endsWith(".java") && !relativePath.endsWith("package-info.java")
                && !relativePath.endsWith("module-info.java");
    }

    @Override
    public FileAnalysis analyze(SourceFile file) {
        ParseResult<CompilationUnit> parsed = parser.parse(file.text());
        if (parsed.getResult().isEmpty()) {
            return FileAnalysis.empty();
        }
        CompilationUnit cu = parsed.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Resolver resolver = new Resolver(pkg, cu.getImports());
        List<Component> components = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        boolean testFile = TEST_PATH.matcher(file.path()).find();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            new TypeReader(file, pkg, resolver, type, testFile, components, references).read();
        }
        return new FileAnalysis(components, references, testFile ? List.of() : accessRules(cu, file.path()));
    }

    /** What an endpoint's authorization says when nothing more specific than the global rule is known. */
    public static final String GLOBAL_RULE = "autenticato (regola globale)";

    private static final Set<String> MATCHERS = Set.of("requestMatchers", "antMatchers", "mvcMatchers", "regexMatchers",
            "anyRequest");
    private static final Set<String> DECISIONS = Set.of("permitAll", "authenticated", "fullyAuthenticated", "denyAll",
            "anonymous", "hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority", "access", "rememberMe");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /**
     * PHASE 32 (TD-62): the rules a Spring Security configuration declares by path --
     * {@code requestMatchers(…).hasRole(…)}, {@code anyRequest().authenticated()} -- in
     * the order they are written, since the first that matches decides. Patterns are
     * string literals or constants of the same file ({@code ADMIN_ONLY}); a constant
     * of another class is not read, and its rule matches nothing.
     */
    static List<AccessRule> accessRules(CompilationUnit cu, String path) {
        List<MethodCallExpr> decisions = new ArrayList<>(cu.findAll(MethodCallExpr.class, call ->
                DECISIONS.contains(call.getNameAsString()) && call.getScope().filter(Expression::isMethodCallExpr)
                        .map(scope -> MATCHERS.contains(scope.asMethodCallExpr().getNameAsString())).orElse(false)));
        // the name, not the call: a call in a chain begins where the whole chain begins
        decisions.sort(java.util.Comparator.comparing(call -> call.getName().getBegin().orElse(new com.github.javaparser.Position(0, 0))));
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        List<AccessRule> rules = new ArrayList<>();
        for (MethodCallExpr decision : decisions) {
            MethodCallExpr matcher = decision.getScope().get().asMethodCallExpr();
            String method = null;
            List<String> patterns = new ArrayList<>();
            if (matcher.getNameAsString().equals("anyRequest")) {
                patterns.add("/**");
            }
            for (Expression argument : matcher.getArguments()) {
                if (argument.isFieldAccessExpr() && argument.asFieldAccessExpr().getScope().toString().endsWith("HttpMethod")) {
                    method = argument.asFieldAccessExpr().getNameAsString();
                } else if (argument.isStringLiteralExpr()) {
                    patterns.add(argument.asStringLiteralExpr().asString());
                } else if (argument.isNameExpr()) {
                    List<String> constant = constant(cu, argument.asNameExpr().getNameAsString());
                    if (constant.isEmpty() && HTTP_METHODS.contains(argument.asNameExpr().getNameAsString())) {
                        method = argument.asNameExpr().getNameAsString();
                    }
                    patterns.addAll(constant);
                }
            }
            if (patterns.isEmpty()) {
                continue;
            }
            String arguments = String.join(", ", decision.getArguments().stream()
                    .map(a -> a.isStringLiteralExpr() ? "'" + a.asStringLiteralExpr().asString() + "'" : a.toString())
                    .toList());
            rules.add(new AccessRule(method, List.copyOf(patterns), decision.getNameAsString() + "(" + arguments + ")",
                    fileName + ":" + decision.getName().getBegin().map(p -> p.line).orElse(0)));
        }
        return rules;
    }

    /** The string values of a constant of this file: {@code String X = "…"} or {@code String[] X = {"…", …}}. */
    private static List<String> constant(CompilationUnit cu, String name) {
        for (VariableDeclarator variable : cu.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name))) {
            Optional<Expression> initializer = variable.getInitializer();
            if (initializer.isEmpty()) {
                continue;
            }
            Expression value = initializer.get();
            if (value.isArrayCreationExpr() && value.asArrayCreationExpr().getInitializer().isPresent()) {
                value = value.asArrayCreationExpr().getInitializer().get();
            }
            if (value.isStringLiteralExpr()) {
                return List.of(value.asStringLiteralExpr().asString());
            }
            if (value.isArrayInitializerExpr()) {
                return value.asArrayInitializerExpr().getValues().stream().filter(Expression::isStringLiteralExpr)
                        .map(v -> v.asStringLiteralExpr().asString()).toList();
            }
        }
        return List.of();
    }

    public static String keyOf(String qualifiedName) {
        return "java:" + qualifiedName;
    }

    // --- resolution of simple names, the way a reader does it -----------------

    /** Candidate keys for a simple type name used in one compilation unit. */
    static final class Resolver {
        private final String pkg;
        private final Map<String, String> explicit = new HashMap<>();
        private final List<String> wildcards = new ArrayList<>();

        Resolver(String pkg, NodeList<ImportDeclaration> imports) {
            this.pkg = pkg;
            for (ImportDeclaration i : imports) {
                if (i.isStatic()) {
                    continue;
                }
                String name = i.getNameAsString();
                if (i.isAsterisk()) {
                    wildcards.add(name);
                } else {
                    explicit.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
            }
        }

        List<String> candidates(String simpleName) {
            if (simpleName == null || simpleName.isEmpty() || JAVA_LANG.contains(simpleName)
                    || !Character.isUpperCase(simpleName.charAt(0))) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            String imported = explicit.get(simpleName);
            if (imported != null) {
                out.add(keyOf(imported));
                // import a.b.Outer.Inner: a nested type is not a block, its top-level type is
                String outer = imported;
                while (outer.lastIndexOf('.') > 0) {
                    outer = outer.substring(0, outer.lastIndexOf('.'));
                    String last = outer.substring(outer.lastIndexOf('.') + 1);
                    if (last.isEmpty() || !Character.isUpperCase(last.charAt(0))) {
                        break;
                    }
                    out.add(keyOf(outer));
                }
                return out;
            }
            out.add(keyOf(pkg.isEmpty() ? simpleName : pkg + "." + simpleName));
            wildcards.forEach(w -> out.add(keyOf(w + "." + simpleName)));
            return out;
        }

        /** Whether the name is imported from somewhere, i.e. is a library type when it does not resolve. */
        boolean importedExplicitly(String simpleName) {
            return explicit.containsKey(simpleName);
        }

        String explicitImport(String simpleName) {
            return explicit.get(simpleName);
        }
    }

    // --- one top-level type ---------------------------------------------------

    private static final class TypeReader {
        private final SourceFile file;
        private final String pkg;
        private final Resolver resolver;
        private final TypeDeclaration<?> type;
        private final boolean testFile;
        private final List<Component> components;
        private final List<Reference> references;
        private final String key;
        private final String name;
        /** Field and constructor-parameter names to their type, for calls on {@code this.x} and {@code x}. */
        private final Map<String, Type> members = new LinkedHashMap<>();

        TypeReader(SourceFile file, String pkg, Resolver resolver, TypeDeclaration<?> type, boolean testFile,
                   List<Component> components, List<Reference> references) {
            this.file = file;
            this.pkg = pkg;
            this.resolver = resolver;
            this.type = type;
            this.testFile = testFile;
            this.components = components;
            this.references = references;
            this.name = type.getNameAsString();
            this.key = keyOf(pkg.isEmpty() ? name : pkg + "." + name);
        }

        void read() {
            List<String> annotations = annotationNames(type);
            List<String> extendsTypes = new ArrayList<>();
            List<String> implementsTypes = new ArrayList<>();
            List<ClassOrInterfaceType> extended = new ArrayList<>();
            if (type instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t -> { extendsTypes.add(t.asString()); extended.add(t); });
                c.getImplementedTypes().forEach(t -> implementsTypes.add(t.asString()));
            } else if (type instanceof EnumDeclaration e) {
                e.getImplementedTypes().forEach(t -> implementsTypes.add(t.asString()));
            } else if (type instanceof RecordDeclaration r) {
                r.getImplementedTypes().forEach(t -> implementsTypes.add(t.asString()));
            }
            NodeKind kind = kindOf(annotations, extended);

            // --- fields and constructor injection
            List<CodeStructure.CodeMember> fields = new ArrayList<>();
            Set<String> dependencies = new LinkedHashSet<>();
            for (FieldDeclaration f : type.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    members.put(v.getNameAsString(), v.getType());
                    fields.add(new CodeStructure.CodeMember(v.getNameAsString(), v.getType().asString(),
                            visibility(f.getAccessSpecifier().asString()), annotationNames(f)));
                    if (!f.isStatic()) {
                        dependencyReference(v.getType(), f, RelationType.USES, "campo " + v.getNameAsString(), dependencies);
                    }
                }
            }
            if (type instanceof RecordDeclaration r) {
                for (Parameter p : r.getParameters()) {
                    members.put(p.getNameAsString(), p.getType());
                    fields.add(new CodeStructure.CodeMember(p.getNameAsString(), p.getType().asString(), "public",
                            annotationNames(p)));
                    dependencyReference(p.getType(), p, RelationType.USES, "componente " + p.getNameAsString(), dependencies);
                }
            }
            for (ConstructorDeclaration c : type.getConstructors()) {
                for (Parameter p : c.getParameters()) {
                    members.putIfAbsent(p.getNameAsString(), p.getType());
                    dependencyReference(p.getType(), p, RelationType.USES, "iniettato nel costruttore", dependencies);
                }
            }

            // --- structure: extends, implements
            for (ClassOrInterfaceType t : extended) {
                relate(t.getNameAsString(), RelationType.EXTENDS, null, t, true);
                if (kind == NodeKind.REPOSITORY) {
                    // JpaRepository<Task, Long>: the repository maps the entity.
                    t.getTypeArguments().filter(a -> !a.isEmpty()).ifPresent(args ->
                            args.get(0).findAll(ClassOrInterfaceType.class).stream().findFirst().ifPresent(entity ->
                                    relate(entity.getNameAsString(), RelationType.MAPS_TO, "entità gestita", entity, true)));
                }
            }
            implementedTypes().forEach(t -> relate(t.getNameAsString(), RelationType.IMPLEMENTS, null, t, true));

            // --- methods, endpoints, calls
            String prefix = kind == NodeKind.CONTROLLER ? requestPrefix(type) : "";
            List<CodeStructure.CodeMethod> methods = new ArrayList<>();
            Set<String> thrown = new LinkedHashSet<>();
            List<String> endpointLines = new ArrayList<>();
            Map<String, List<MethodCallExpr>> callsByTarget = new LinkedHashMap<>();
            for (MethodDeclaration m : type.getMethods()) {
                CodeStructure.CodeMethod method = method(m, callsByTarget, thrown);
                methods.add(method);
                if (kind == NodeKind.CONTROLLER) {
                    endpointLines.addAll(endpoints(m, prefix, method));
                }
            }
            for (ConstructorDeclaration c : type.getConstructors()) {
                c.findAll(ObjectCreationExpr.class).forEach(o -> relate(o.getType().getNameAsString(),
                        RelationType.CREATES, "new " + o.getType().getNameAsString(), o, false));
            }
            callsByTarget.forEach((simple, calls) -> {
                // One CALLS edge per called type, labelled with the methods it calls,
                // pointing at the first call in the code.
                String labels = String.join(", ", calls.stream().map(c -> c.getNameAsString() + "()").distinct().toList());
                references.add(new Reference(key, resolver.candidates(simple), RelationType.CALLS, labels, file.path(),
                        line(calls.get(0)), JavaPseudocode.shorten(calls.get(0)), internal(simple)));
            });

            // --- tests: what they cover
            if (kind == NodeKind.TEST) {
                testCoverage();
            }

            // --- entities: their table
            String table = null;
            List<String> columns = new ArrayList<>();
            if (kind == NodeKind.ENTITY) {
                table = annotationValue(type, "Table", "name").orElse(snake(name));
                references.add(new Reference(key, List.of("table:" + table.toLowerCase(Locale.ROOT)),
                        RelationType.ENTITY_TO_TABLE, table, file.path(), line(type), "@Table(name = \"" + table + "\")",
                        false));
                for (FieldDeclaration f : type.getFields()) {
                    if (!f.isStatic() && !f.isTransient()) {
                        f.getVariables().forEach(v -> columns.add(annotationValue(f, "Column", "name")
                                .or(() -> annotationValue(f, "JoinColumn", "name")).orElse(snake(v.getNameAsString()))));
                    }
                }
            }

            CodeStructure structure = CodeStructure.builder()
                    .packageName(pkg).className(name)
                    .extendsType(extendsTypes.isEmpty() ? null : String.join(", ", extendsTypes))
                    .implementsTypes(implementsTypes).annotations(annotations).fields(fields).methods(methods)
                    .dependencies(List.copyOf(dependencies)).exceptions(List.copyOf(thrown))
                    .table(table).columns(columns)
                    .build();
            components.add(new Component(key, kind, name, file.path(), "java", description(kind, methods, dependencies, endpointLines),
                    pseudocode(kind, methods, endpointLines), structure, begin(type), end(type)));
        }

        // --- kinds -----------------------------------------------------------

        private NodeKind kindOf(List<String> annotations, List<ClassOrInterfaceType> extended) {
            Set<String> a = new LinkedHashSet<>(annotations);
            if (testFile || name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")) {
                return NodeKind.TEST;
            }
            if (a.contains("RestController") || a.contains("Controller")) {
                return NodeKind.CONTROLLER;
            }
            if (a.contains("Repository") || extended.stream().anyMatch(t -> REPOSITORY_BASES.contains(t.getNameAsString()))) {
                return NodeKind.REPOSITORY;
            }
            if (a.contains("Entity") || a.contains("Embeddable") || a.contains("MappedSuperclass")) {
                return NodeKind.ENTITY;
            }
            if (a.contains("Service")) {
                return NodeKind.SERVICE;
            }
            if (a.contains("Configuration") || a.contains("ConfigurationProperties") || a.contains("SpringBootApplication")
                    || name.endsWith("Configuration") || name.endsWith("Config")) {
                return NodeKind.CONFIGURATION;
            }
            if (type instanceof EnumDeclaration) {
                return NodeKind.ENUM;
            }
            if (type instanceof RecordDeclaration || name.endsWith("Dto") || name.endsWith("DTO")
                    || name.endsWith("Request") || name.endsWith("Response")) {
                return NodeKind.DTO;
            }
            if (type instanceof ClassOrInterfaceDeclaration c && c.isInterface()) {
                return NodeKind.INTERFACE;
            }
            return NodeKind.CLASS;
        }

        private List<ClassOrInterfaceType> implementedTypes() {
            if (type instanceof ClassOrInterfaceDeclaration c) {
                return c.getImplementedTypes();
            }
            if (type instanceof EnumDeclaration e) {
                return e.getImplementedTypes();
            }
            if (type instanceof RecordDeclaration r) {
                return r.getImplementedTypes();
            }
            return List.of();
        }

        // --- methods ---------------------------------------------------------

        private CodeStructure.CodeMethod method(MethodDeclaration m, Map<String, List<MethodCallExpr>> callsByTarget,
                                           Set<String> thrown) {
            Map<String, Type> scope = new HashMap<>(members);
            Set<String> locals = new HashSet<>();
            m.getParameters().forEach(p -> { scope.put(p.getNameAsString(), p.getType()); locals.add(p.getNameAsString()); });
            m.findAll(VariableDeclarator.class).forEach(v -> { scope.put(v.getNameAsString(), v.getType()); locals.add(v.getNameAsString()); });

            List<String> calls = new ArrayList<>();
            Map<String, String> targets = new LinkedHashMap<>();
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                Optional<String> receiver = receiverName(call);
                if (receiver.isEmpty()) {
                    continue;
                }
                String r = receiver.get();
                Type t = scope.get(r);
                boolean isStaticCall = t == null && Character.isUpperCase(r.charAt(0));
                if (t == null && !isStaticCall) {
                    continue;
                }
                String label = call.getNameAsString();
                calls.add(r + "." + label + "()");
                String simple = t != null ? mainTypeName(t) : r;
                if (!mayBeProject(simple) || simple.equals(name)) {
                    continue;
                }
                targets.putIfAbsent(simple, String.join("|", resolver.candidates(simple)));
                if (members.containsKey(r) && !locals.contains(r)) {
                    callsByTarget.computeIfAbsent(simple, k -> new ArrayList<>()).add(call);
                } else {
                    // a call on a parameter, a local or a type: the class still calls it
                    references.add(new Reference(key, resolver.candidates(simple), RelationType.CALLS, label + "()",
                            file.path(), line(call), JavaPseudocode.shorten(call), internal(simple)));
                }
            }
            for (ObjectCreationExpr o : m.findAll(ObjectCreationExpr.class)) {
                relate(o.getType().getNameAsString(), RelationType.CREATES, "new " + o.getType().getNameAsString(), o, false);
            }
            List<String> exceptions = new ArrayList<>();
            m.getThrownExceptions().forEach(t -> exceptions.add(t.asString()));
            for (ThrowStmt t : m.findAll(ThrowStmt.class)) {
                if (t.getExpression() instanceof ObjectCreationExpr o) {
                    exceptions.add(o.getType().getNameAsString());
                }
            }
            // optional.orElseThrow(() -> new NotFoundException(id)) throws as surely as a throw
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("orElseThrow")) {
                    call.getArguments().forEach(a -> a.findAll(ObjectCreationExpr.class)
                            .forEach(o -> exceptions.add(o.getType().getNameAsString())));
                }
            }
            List<String> distinctExceptions = List.copyOf(new LinkedHashSet<>(exceptions));
            thrown.addAll(distinctExceptions);
            List<String> params = m.getParameters().stream().map(p -> p.getType().asString() + " " + p.getNameAsString()).toList();
            String signature = m.getType().asString() + " " + m.getNameAsString() + "(" + String.join(", ", params) + ")";
            return new CodeStructure.CodeMethod(m.getNameAsString(), signature, visibility(m.getAccessSpecifier().asString()),
                    m.getType().asString(), params, annotationNames(m), distinctExceptions, List.copyOf(new LinkedHashSet<>(calls)),
                    List.copyOf(targets.values()), m.isStatic(), begin(m), end(m), JavaPseudocode.of(m.getBody().orElse(null)));
        }

        private static Optional<String> receiverName(MethodCallExpr call) {
            Optional<Expression> scope = call.getScope();
            if (scope.isEmpty()) {
                return Optional.empty();
            }
            Expression s = scope.get();
            if (s instanceof NameExpr n) {
                return Optional.of(n.getNameAsString());
            }
            if (s instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
                return Optional.of(f.getNameAsString());
            }
            return Optional.empty();
        }

        // --- endpoints -------------------------------------------------------

        private List<String> endpoints(MethodDeclaration m, String prefix, CodeStructure.CodeMethod method) {
            List<String> summaries = new ArrayList<>();
            for (AnnotationExpr a : m.getAnnotations()) {
                String an = simpleName(a.getNameAsString());
                if (!MAPPINGS.contains(an)) {
                    continue;
                }
                String httpMethod = switch (an) {
                    case "GetMapping" -> "GET";
                    case "PostMapping" -> "POST";
                    case "PutMapping" -> "PUT";
                    case "DeleteMapping" -> "DELETE";
                    case "PatchMapping" -> "PATCH";
                    default -> memberValues(a, "method").stream().findFirst()
                            .map(v -> v.substring(v.lastIndexOf('.') + 1)).orElse("ANY");
                };
                List<String> paths = memberValues(a, "value");
                if (paths.isEmpty()) {
                    paths = memberValues(a, "path");
                }
                if (paths.isEmpty()) {
                    paths = List.of("");
                }
                for (String path : paths) {
                    String route = normalizeRoute(prefix + "/" + path);
                    summaries.add(httpMethod + " " + route + " → " + m.getNameAsString() + "()");
                    endpoint(m, method, httpMethod, route);
                }
            }
            return summaries;
        }

        private void endpoint(MethodDeclaration m, CodeStructure.CodeMethod method, String httpMethod, String route) {
            String endpointKey = "endpoint:" + key.substring("java:".length()) + "#" + m.getNameAsString() + ":"
                    + httpMethod + " " + route;
            String requestDto = null;
            for (Parameter p : m.getParameters()) {
                if (p.getAnnotationByName("RequestBody").isPresent()) {
                    requestDto = p.getType().asString();
                    String simple = mainTypeName(p.getType());
                    List<String> candidates = resolver.candidates(simple);
                    if (mayBeProject(simple)) {
                        references.add(new Reference(endpointKey, candidates,
                                p.getAnnotationByName("Valid").isPresent() ? RelationType.VALIDATES : RelationType.MAPS_TO,
                                "request body", file.path(), line(p), JavaPseudocode.shorten(p), false));
                    }
                }
            }
            Type returned = m.getType();
            String responseDto = returned.isVoidType() ? null : returned.asString();
            String responseSimple = mainTypeName(returned);
            if (!returned.isVoidType()) {
                List<String> candidates = resolver.candidates(responseSimple);
                if (mayBeProject(responseSimple)) {
                    references.add(new Reference(endpointKey, candidates, RelationType.MAPS_TO, "response",
                            file.path(), line(m), responseDto, false));
                }
            }
            // The services the handler delegates to: calls on the controller's fields.
            Set<String> services = new LinkedHashSet<>();
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                receiverName(call).filter(members::containsKey).ifPresent(field -> {
                    String simple = mainTypeName(members.get(field));
                    List<String> candidates = resolver.candidates(simple);
                    if (mayBeProject(simple)) {
                        services.add(simple);
                        references.add(new Reference(endpointKey, candidates, RelationType.ENDPOINT_TO_SERVICE,
                                call.getNameAsString() + "()", file.path(), line(call), JavaPseudocode.shorten(call), false));
                    }
                });
            }
            references.add(new Reference(endpointKey, List.of(key), RelationType.BELONGS_TO, m.getNameAsString() + "()",
                    file.path(), line(m), null, false));

            String body = m.getBody().map(Node::toString).orElse("");
            Set<String> statusCodes = new LinkedHashSet<>();
            annotationValue(m, "ResponseStatus", "value").or(() -> annotationValue(m, "ResponseStatus", "code"))
                    .ifPresent(v -> statusCodes.add(httpStatus(v)));
            if (body.contains(".created(")) statusCodes.add("201 Created");
            if (body.contains(".accepted(")) statusCodes.add("202 Accepted");
            if (body.contains(".noContent(")) statusCodes.add("204 No Content");
            if (statusCodes.isEmpty() || body.contains(".ok(") || body.contains("ResponseEntity.ok")) {
                List<String> withOk = new ArrayList<>(List.of("200 OK"));
                withOk.addAll(statusCodes);
                statusCodes.clear();
                statusCodes.addAll(withOk);
            }
            if (m.getParameters().stream().anyMatch(p -> p.toString().contains("IF_MATCH"))) {
                statusCodes.add("412 Precondition Failed");
                statusCodes.add("428 Precondition Required");
            }
            String authorization = annotationValue(m, "PreAuthorize", "value")
                    .or(() -> annotationValue(type, "PreAuthorize", "value"))
                    .or(() -> annotationValue(m, "Secured", "value"))
                    .orElse(GLOBAL_RULE);
            CodeStructure structure = CodeStructure.builder()
                    .httpMethod(httpMethod).route(route).controller(name).requestDto(requestDto).responseDto(responseDto)
                    .service(services.isEmpty() ? null : String.join(", ", services)).authorization(authorization)
                    .statusCodes(List.copyOf(statusCodes)).signature(method.signature()).inputs(method.parameters())
                    .output(method.returnType()).annotations(method.annotations()).exceptions(method.exceptions())
                    .calls(method.calls())
                    .build();
            String description = javadocSentence(m).orElse("Gestito da " + name + "." + m.getNameAsString() + "()"
                    + (services.isEmpty() ? "." : ", delega a " + String.join(", ", services) + "."));
            components.add(new Component(endpointKey, NodeKind.ENDPOINT, httpMethod + " " + route, file.path(), "java",
                    description, method.pseudocode(), structure, begin(m), end(m)));
        }

        private String requestPrefix(TypeDeclaration<?> t) {
            return t.getAnnotationByName("RequestMapping").map(a -> {
                List<String> values = memberValues(a, "value");
                if (values.isEmpty()) {
                    values = memberValues(a, "path");
                }
                return values.isEmpty() ? "" : values.get(0);
            }).orElse("");
        }

        // --- tests -----------------------------------------------------------

        private void testCoverage() {
            // By name: TaskServiceTest covers TaskService.
            String covered = name.replaceAll("(Tests?|IT)$", "");
            if (!covered.equals(name) && !covered.isEmpty()) {
                List<String> candidates = resolver.candidates(covered);
                if (!candidates.isEmpty()) {
                    references.add(new Reference(key, candidates, RelationType.TEST_COVERS, "per nome", file.path(),
                            line(type), null, false));
                }
            }
            // By route: the /api/... literals the test calls cover the endpoints they match.
            // "/api/projects/" + id + "/code-graph" is folded, from the AST, into
            // /api/projects/{}/code-graph; get(...), post(...) around it give the method.
            Set<String> routes = new LinkedHashSet<>();
            for (StringLiteralExpr s : type.findAll(StringLiteralExpr.class)) {
                if (!API_LITERAL.matcher(s.getValue()).find()) {
                    continue;
                }
                Expression whole = s;
                while (whole.getParentNode().orElse(null) instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
                    whole = b;
                }
                StringBuilder route = new StringBuilder();
                fold(whole, route);
                String pattern = route.toString().replaceAll("\\?.*$", "").replaceAll("(\\{})+", "{}");
                String method = "*";
                Node parent = whole.getParentNode().orElse(null);
                if (parent instanceof MethodCallExpr call && Set.of("get", "post", "put", "delete", "patch")
                        .contains(call.getNameAsString())) {
                    method = call.getNameAsString().toUpperCase(Locale.ROOT);
                }
                if (routes.add(method + " " + pattern)) {
                    references.add(new Reference(key, List.of("route:" + method + " " + pattern), RelationType.TEST_COVERS,
                            (method.equals("*") ? "" : method + " ") + pattern, file.path(), line(s), null, false));
                }
            }
            // By injection: @Autowired fields of project types, other than the harness.
            for (FieldDeclaration f : type.getFields()) {
                if (f.getAnnotationByName("Autowired").isPresent() || f.getAnnotationByName("MockitoBean").isPresent()) {
                    f.getVariables().forEach(v -> {
                        String simple = mainTypeName(v.getType());
                        if (mayBeProject(simple)) {
                            references.add(new Reference(key, resolver.candidates(simple), RelationType.TEST_COVERS,
                                    v.getNameAsString(), file.path(), line(f), null, false));
                        }
                    });
                }
            }
        }

        // --- references ------------------------------------------------------

        /** A USES edge for each project type a field, component or constructor parameter mentions. */
        private void dependencyReference(Type t, Node where, RelationType relation, String label, Set<String> dependencies) {
            for (ClassOrInterfaceType c : typesIn(t)) {
                String simple = c.getNameAsString();
                if (!mayBeProject(simple) || simple.equals(name)) {
                    continue;
                }
                dependencies.add(simple);
                references.add(new Reference(key, resolver.candidates(simple), relation, label, file.path(), line(where),
                        JavaPseudocode.shorten(where), internal(simple)));
            }
        }

        private void relate(String simple, RelationType relation, String label, Node where, boolean internal) {
            if (simple.equals(name)) {
                return;
            }
            if (mayBeProject(simple)) {
                references.add(new Reference(key, resolver.candidates(simple), relation, label, file.path(), line(where),
                        JavaPseudocode.shorten(where), internal && internal(simple)));
            }
        }

        /** A name that could be a type of the project: not java.lang, not imported from a library. */
        private boolean mayBeProject(String simple) {
            return !resolver.candidates(simple).isEmpty()
                    && (!resolver.importedExplicitly(simple) || isProjectImport(resolver.explicitImport(simple)));
        }

        /** Imported explicitly from the project's own packages: if it does not resolve, something is missing. */
        private boolean internal(String simple) {
            return resolver.importedExplicitly(simple) && isProjectImport(resolver.explicitImport(simple));
        }

        /** An import is the project's when it shares the first two segments of this file's package. */
        private boolean isProjectImport(String fqn) {
            String[] mine = pkg.split("\\.");
            if (fqn == null || mine.length < 2) {
                return false;
            }
            return fqn.startsWith(mine[0] + "." + mine[1] + ".");
        }

        // --- prose -----------------------------------------------------------

        private String description(NodeKind kind, List<CodeStructure.CodeMethod> methods, Set<String> dependencies,
                                   List<String> endpoints) {
            Optional<String> doc = javadocSentence(type);
            if (doc.isPresent()) {
                return doc.get();
            }
            List<String> publicMethods = methods.stream().filter(m -> "public".equals(m.visibility()))
                    .map(CodeStructure.CodeMethod::name).distinct().limit(6).toList();
            String deps = dependencies.isEmpty() ? "" : " Dipende da " + String.join(", ", dependencies.stream().limit(5).toList()) + ".";
            return switch (kind) {
                case CONTROLLER -> "Controller REST con " + endpoints.size() + " endpoint." + deps;
                case SERVICE -> "Logica applicativa" + (publicMethods.isEmpty() ? "." : ": " + String.join(", ", publicMethods) + ".") + deps;
                case REPOSITORY -> "Accesso ai dati persistenti.";
                case ENTITY -> "Entità persistente.";
                case DTO -> "Oggetto di trasferimento dati.";
                case TEST -> "Test automatico.";
                case CONFIGURATION -> "Configurazione dell'applicazione.";
                case ENUM -> "Enumerazione.";
                case INTERFACE -> "Interfaccia" + (publicMethods.isEmpty() ? "." : ": " + String.join(", ", methods.stream().map(CodeStructure.CodeMethod::name).distinct().limit(6).toList()) + ".");
                default -> "Classe" + (publicMethods.isEmpty() ? "." : " con " + String.join(", ", publicMethods) + ".") + deps;
            };
        }

        private static String pseudocode(NodeKind kind, List<CodeStructure.CodeMethod> methods, List<String> endpoints) {
            StringBuilder out = new StringBuilder();
            if (kind == NodeKind.CONTROLLER && !endpoints.isEmpty()) {
                endpoints.forEach(e -> out.append("ON ").append(e).append('\n'));
                return out.toString().strip();
            }
            int lines = 0;
            for (CodeStructure.CodeMethod m : methods) {
                if (!"public".equals(m.visibility()) && kind != NodeKind.TEST) {
                    continue;
                }
                out.append(m.name()).append('(').append(String.join(", ", m.parameters().stream()
                        .map(p -> p.substring(p.lastIndexOf(' ') + 1)).toList())).append("):\n");
                List<String> body = m.pseudocode() == null || m.pseudocode().isBlank() ? List.of("(astratto)")
                        : m.pseudocode().lines().limit(6).toList();
                for (String l : body) {
                    out.append("  ").append(l).append('\n');
                }
                if (m.pseudocode() != null && m.pseudocode().lines().count() > 6) {
                    out.append("  …\n");
                }
                if (++lines >= 12) {
                    out.append("…\n");
                    break;
                }
            }
            String text = out.toString().strip();
            return text.length() > 7900 ? text.substring(0, 7900) + "\n…" : text;
        }
    }

    // --- helpers ---------------------------------------------------------------

    static Optional<String> javadocSentence(Node node) {
        if (!(node instanceof BodyDeclaration<?> body)) {
            return Optional.empty();
        }
        Optional<Javadoc> doc = body.getComment().filter(c -> c.isJavadocComment()).map(c -> c.asJavadocComment().parse());
        return doc.map(d -> d.getDescription().toText())
                .map(t -> t.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip())
                .filter(t -> !t.isEmpty())
                .map(t -> {
                    int end = t.indexOf(". ");
                    String s = end > 0 ? t.substring(0, end + 1) : t;
                    return s.length() > 400 ? s.substring(0, 399) + "…" : s;
                });
    }

    /** A string concatenation as a route: literals verbatim, everything else one variable segment. */
    static void fold(Expression e, StringBuilder out) {
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            fold(b.getLeft(), out);
            fold(b.getRight(), out);
        } else if (e instanceof StringLiteralExpr s) {
            out.append(s.getValue());
        } else if (e instanceof EnclosedExpr en) {
            fold(en.getInner(), out);
        } else {
            out.append("{}");
        }
    }

    static List<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(a -> simpleName(a.getNameAsString())).toList();
    }

    static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * The values of an annotation member, as strings: literals verbatim, constants
     * of the enclosing type and concatenations evaluated ({@code BASE + "/scan"}),
     * anything else as written.
     */
    static List<String> memberValues(AnnotationExpr a, String member) {
        Expression value = null;
        if (a instanceof SingleMemberAnnotationExpr s && member.equals("value")) {
            value = s.getMemberValue();
        } else if (a instanceof NormalAnnotationExpr n) {
            value = n.getPairs().stream().filter(p -> p.getNameAsString().equals(member)).map(p -> p.getValue())
                    .findFirst().orElse(null);
        }
        if (value == null) {
            return List.of();
        }
        List<Expression> values = value instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(value);
        TypeDeclaration<?> owner = a.findAncestor(TypeDeclaration.class).orElse(null);
        return values.stream().map(v -> constant(v, owner, 0).orElse(v.toString())).toList();
    }

    /** A compile-time string: a literal, a concatenation, or a static final field of the same type. */
    static Optional<String> constant(Expression e, TypeDeclaration<?> owner, int depth) {
        if (depth > 8) {
            return Optional.empty();
        }
        if (e instanceof StringLiteralExpr s) {
            return Optional.of(s.getValue());
        }
        if (e instanceof EnclosedExpr en) {
            return constant(en.getInner(), owner, depth + 1);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = constant(b.getLeft(), owner, depth + 1);
            Optional<String> right = constant(b.getRight(), owner, depth + 1);
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        String name = e instanceof NameExpr n ? n.getNameAsString()
                : e instanceof FieldAccessExpr f && owner != null && f.getScope().toString().equals(owner.getNameAsString())
                ? f.getNameAsString() : null;
        if (name == null || owner == null) {
            return Optional.empty();
        }
        return owner.getFieldByName(name).filter(FieldDeclaration::isStatic)
                .flatMap(f -> f.getVariable(0).getInitializer())
                .flatMap(init -> constant(init, owner, depth + 1));
    }

    static Optional<String> annotationValue(NodeWithAnnotations<?> node, String annotation, String member) {
        return node.getAnnotationByName(annotation).flatMap(a -> memberValues(a, member).stream().findFirst());
    }

    /** {@code List<TaskResponse>} is about TaskResponse; {@code ResponseEntity<Void>} about nothing. */
    static String mainTypeName(Type t) {
        List<ClassOrInterfaceType> all = typesIn(t);
        return all.isEmpty() ? t.asString() : all.get(all.size() - 1).getNameAsString();
    }

    /** Every class type a type mentions, generics included, wrappers from the JDK and Spring left out. */
    static List<ClassOrInterfaceType> typesIn(Type t) {
        List<ClassOrInterfaceType> out = new ArrayList<>();
        for (ClassOrInterfaceType c : t.findAll(ClassOrInterfaceType.class)) {
            String n = c.getNameAsString();
            // Outer.Inner: the scope is visited on its own, and is the block; a.b.Type
            // (package-qualified) is left out -- rare in type positions, and a known limit.
            if (c.getScope().isPresent()) {
                continue;
            }
            if (!Set.of("List", "Set", "Map", "Optional", "Collection", "ResponseEntity", "Iterable", "Stream",
                    "CompletableFuture", "Page", "Supplier", "Function", "Consumer", "Mono", "Flux").contains(n)) {
                out.add(c);
            }
        }
        return out;
    }

    static String normalizeRoute(String route) {
        String r = route.replaceAll("/+", "/");
        if (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r.startsWith("/") ? r : "/" + r;
    }

    static String visibility(String access) {
        return access.isEmpty() ? "package" : access;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String httpStatus(String value) {
        String v = value.substring(value.lastIndexOf('.') + 1);
        return switch (v) {
            case "CREATED" -> "201 Created";
            case "ACCEPTED" -> "202 Accepted";
            case "NO_CONTENT" -> "204 No Content";
            default -> v;
        };
    }

    static Integer line(Node n) {
        return n == null ? null : n.getBegin().map(p -> p.line).orElse(null);
    }

    /** Where a component starts for a reader: at its Javadoc, when it has one. */
    static Integer begin(Node n) {
        return n.getComment().flatMap(Node::getBegin).map(p -> p.line).orElse(line(n));
    }

    static Integer end(Node n) {
        return n.getEnd().map(p -> p.line).orElse(null);
    }
}
