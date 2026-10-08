package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PHASE 32 (TD-58): what every Java file of the project declares, read once before
 * the files are read one by one -- a type index in place of a symbol solver. It
 * knows, for each type of the project, its supertypes, its fields and what its
 * methods return; with it a call on an inherited field, on the result of another
 * call ({@code a.b().c()}) or on a package-qualified type reaches the type it is
 * made on, and a method can name the methods it invokes.
 */
final class JavaProjectIndex {

    /** One type of the project; {@code supertypes} as written, resolved with its file's imports. */
    record TypeInfo(String key, JavaAstPlugin.Resolver resolver, List<ClassOrInterfaceType> supertypes,
                    Map<String, Type> fields, Map<String, Type> returns) {
    }

    /** A member found in a type or in one of its supertypes, with the type that declares it. */
    record Member(Type type, TypeInfo owner) {
    }

    private static final int MAX_DEPTH = 12;

    private final Map<String, TypeInfo> types = new HashMap<>();
    private final Map<String, CompilationUnit> parsed = new HashMap<>();

    static JavaProjectIndex of(JavaParser parser, List<SourceFile> files) {
        JavaProjectIndex index = new JavaProjectIndex();
        for (SourceFile file : files) {
            ParseResult<CompilationUnit> result;
            try {
                result = parser.parse(file.text());
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (result.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            index.parsed.put(file.path(), cu);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            JavaAstPlugin.Resolver resolver = new JavaAstPlugin.Resolver(pkg, cu.getImports());
            for (TypeDeclaration<?> type : cu.getTypes()) {
                index.add(type, pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString(), resolver);
            }
        }
        return index;
    }

    private void add(TypeDeclaration<?> type, String fqn, JavaAstPlugin.Resolver resolver) {
        List<ClassOrInterfaceType> supertypes = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration c) {
            supertypes.addAll(c.getExtendedTypes());
            supertypes.addAll(c.getImplementedTypes());
        } else if (type instanceof EnumDeclaration e) {
            supertypes.addAll(e.getImplementedTypes());
        } else if (type instanceof RecordDeclaration r) {
            supertypes.addAll(r.getImplementedTypes());
        }
        Map<String, Type> fields = new HashMap<>();
        for (FieldDeclaration f : type.getFields()) {
            f.getVariables().forEach(v -> fields.put(v.getNameAsString(), v.getType()));
        }
        Map<String, Type> returns = new HashMap<>();
        if (type instanceof RecordDeclaration r) {
            r.getParameters().forEach(p -> {
                fields.put(p.getNameAsString(), p.getType());
                returns.put(p.getNameAsString(), p.getType());   // the accessor
            });
        }
        for (MethodDeclaration m : type.getMethods()) {
            returns.putIfAbsent(m.getNameAsString(), m.getType());
        }
        types.put(JavaAstPlugin.keyOf(fqn), new TypeInfo(JavaAstPlugin.keyOf(fqn), resolver, supertypes, fields, returns));
        // A nested type is indexed too, for what its methods return; it is not a block of its own.
        for (var member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                add(nested, fqn + "." + nested.getNameAsString(), resolver);
            }
        }
    }

    Optional<CompilationUnit> parsed(String path) {
        return Optional.ofNullable(parsed.get(path));
    }

    Optional<TypeInfo> type(String key) {
        return Optional.ofNullable(types.get(key));
    }

    /** The first candidate key that is a type of the project. */
    Optional<String> resolve(List<String> candidates) {
        return candidates.stream().filter(types::containsKey).findFirst();
    }

    /** A type named by its full name ({@code com.acme.Foo}), when it is the project's. */
    Optional<String> byName(String qualified) {
        String key = JavaAstPlugin.keyOf(qualified);
        return types.containsKey(key) ? Optional.of(key) : Optional.empty();
    }

    /** The project type a declared type names, through the imports of the file that wrote it. */
    Optional<String> keyOf(Type type, JavaAstPlugin.Resolver resolver) {
        if (type instanceof ClassOrInterfaceType c && c.getScope().isPresent()) {
            Optional<String> qualified = byName(c.getNameWithScope());
            if (qualified.isPresent()) {
                return qualified;
            }
        }
        String simple = JavaAstPlugin.mainTypeName(type);
        return resolve(resolver.candidates(simple));
    }

    /** A field of the type, or inherited from one of its supertypes in the project. */
    Optional<Member> field(String key, String name) {
        return find(key, name, true, new HashSet<>(), 0);
    }

    /** What a method of the type, or of one of its supertypes in the project, returns. */
    Optional<Member> returns(String key, String method) {
        return find(key, method, false, new HashSet<>(), 0);
    }

    /** The type of the project that declares a method the type has, its own or inherited. */
    Optional<String> declaring(String key, String method) {
        return returns(key, method).map(m -> m.owner().key());
    }

    private static final Set<String> REPOSITORIES = Set.of("JpaRepository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository", "Repository");

    /** The entity a Spring Data repository of the project manages: the first type argument of its base. */
    Optional<String> managedEntity(String key) {
        TypeInfo info = types.get(key);
        if (info == null) {
            return Optional.empty();
        }
        for (ClassOrInterfaceType supertype : info.supertypes()) {
            if (REPOSITORIES.contains(supertype.getNameAsString()) && supertype.getTypeArguments().isPresent()
                    && !supertype.getTypeArguments().get().isEmpty()) {
                return keyOf(supertype.getTypeArguments().get().get(0), info.resolver());
            }
        }
        return Optional.empty();
    }

    private Optional<Member> find(String key, String name, boolean field, Set<String> seen, int depth) {
        TypeInfo info = types.get(key);
        if (info == null || depth > MAX_DEPTH || !seen.add(key)) {
            return Optional.empty();
        }
        Type own = (field ? info.fields() : info.returns()).get(name);
        if (own != null) {
            return Optional.of(new Member(own, info));
        }
        for (ClassOrInterfaceType supertype : info.supertypes()) {
            Optional<String> parent = keyOf(supertype, info.resolver());
            if (parent.isPresent()) {
                Optional<Member> found = find(parent.get(), name, field, seen, depth + 1);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
