package com.aicompany.backend.blueprint.model;

import java.util.List;

/**
 * The structured fields of a block (ADR-033 §2): the parts of code a component is
 * made of, as the parser read them -- or as the operator wrote them for a manual
 * block. One flat shape for every kind, every field optional: a class fills the
 * class section, a method the method section, an endpoint the endpoint section.
 * Kept as JSON in {@code blueprint_nodes.structure}.
 */
public record CodeStructure(
        // --- class, interface, enum, record ---------------------------------
        String packageName,
        String className,
        String extendsType,
        List<String> implementsTypes,
        List<String> annotations,
        List<CodeMember> fields,
        List<CodeMethod> methods,
        List<String> dependencies,
        List<String> exceptions,
        List<String> relatedTests,
        // --- method or function ---------------------------------------------
        String signature,
        List<String> inputs,
        String output,
        String visibility,
        List<String> calls,
        List<String> calledBy,
        // --- API endpoint ---------------------------------------------------
        String httpMethod,
        String route,
        String controller,
        String requestDto,
        String responseDto,
        String service,
        String authorization,
        List<String> statusCodes,
        // --- frontend and scripts -------------------------------------------
        List<String> exports,
        List<String> imports,
        List<String> props,
        List<String> hooks,
        // --- database -------------------------------------------------------
        String table,
        List<String> columns) {

    /** A field of a class, or a column of an entity. */
    public record CodeMember(String name, String type, String visibility, List<String> annotations) {
    }

    /**
     * A method as a class lists it; expanding the class turns each into a FUNCTION
     * block. {@code calls} is for people ({@code tasks.save}); {@code callTargets}
     * holds, per called type, the candidate block keys separated by {@code |}, so
     * an expansion can draw the method's CALLS edges without parsing again.
     */
    public record CodeMethod(String name, String signature, String visibility, String returnType, List<String> parameters,
                         List<String> annotations, List<String> exceptions, List<String> calls,
                         List<String> callTargets, boolean isStatic, Integer startLine, Integer endLine,
                         String pseudocode, List<String> invokes) {

        /**
         * {@code invokes}: PHASE 32 (TD-58), the methods of the project this one calls, as
         * {@code com.acme.Type#method} -- the type that declares the method, its own or
         * inherited: the edges of the interprocedural call graph. Empty for a structure
         * read before it existed.
         */
        public CodeMethod {
            invokes = invokes == null ? List.of() : List.copyOf(invokes);
        }

        public CodeMethod(String name, String signature, String visibility, String returnType, List<String> parameters,
                          List<String> annotations, List<String> exceptions, List<String> calls, List<String> callTargets,
                          boolean isStatic, Integer startLine, Integer endLine, String pseudocode) {
            this(name, signature, visibility, returnType, parameters, annotations, exceptions, calls, callTargets, isStatic,
                    startLine, endLine, pseudocode, List.of());
        }
    }

    public static CodeStructure empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.packageName = packageName; b.className = className; b.extendsType = extendsType;
        b.implementsTypes = implementsTypes; b.annotations = annotations; b.fields = fields; b.methods = methods;
        b.dependencies = dependencies; b.exceptions = exceptions; b.relatedTests = relatedTests;
        b.signature = signature; b.inputs = inputs; b.output = output; b.visibility = visibility; b.calls = calls;
        b.calledBy = calledBy; b.httpMethod = httpMethod; b.route = route; b.controller = controller;
        b.requestDto = requestDto; b.responseDto = responseDto; b.service = service; b.authorization = authorization;
        b.statusCodes = statusCodes; b.exports = exports; b.imports = imports; b.props = props; b.hooks = hooks;
        b.table = table; b.columns = columns;
        return b;
    }

    /** Null lists become empty ones: the JSON a client reads never has a null where a list belongs. */
    public CodeStructure normalized() {
        return toBuilder().build();
    }

    /** Fluent construction for the parsers: most blocks fill a handful of the thirty fields. */
    public static final class Builder {
        private String packageName, className, extendsType, signature, output, visibility, httpMethod, route,
                controller, requestDto, responseDto, service, authorization, table;
        private List<String> implementsTypes, annotations, dependencies, exceptions, relatedTests, inputs, calls,
                calledBy, statusCodes, exports, imports, props, hooks, columns;
        private List<CodeMember> fields;
        private List<CodeMethod> methods;

        public Builder packageName(String v) { packageName = v; return this; }
        public Builder className(String v) { className = v; return this; }
        public Builder extendsType(String v) { extendsType = v; return this; }
        public Builder implementsTypes(List<String> v) { implementsTypes = v; return this; }
        public Builder annotations(List<String> v) { annotations = v; return this; }
        public Builder fields(List<CodeMember> v) { fields = v; return this; }
        public Builder methods(List<CodeMethod> v) { methods = v; return this; }
        public Builder dependencies(List<String> v) { dependencies = v; return this; }
        public Builder exceptions(List<String> v) { exceptions = v; return this; }
        public Builder relatedTests(List<String> v) { relatedTests = v; return this; }
        public Builder signature(String v) { signature = v; return this; }
        public Builder inputs(List<String> v) { inputs = v; return this; }
        public Builder output(String v) { output = v; return this; }
        public Builder visibility(String v) { visibility = v; return this; }
        public Builder calls(List<String> v) { calls = v; return this; }
        public Builder calledBy(List<String> v) { calledBy = v; return this; }
        public Builder httpMethod(String v) { httpMethod = v; return this; }
        public Builder route(String v) { route = v; return this; }
        public Builder controller(String v) { controller = v; return this; }
        public Builder requestDto(String v) { requestDto = v; return this; }
        public Builder responseDto(String v) { responseDto = v; return this; }
        public Builder service(String v) { service = v; return this; }
        public Builder authorization(String v) { authorization = v; return this; }
        public Builder statusCodes(List<String> v) { statusCodes = v; return this; }
        public Builder exports(List<String> v) { exports = v; return this; }
        public Builder imports(List<String> v) { imports = v; return this; }
        public Builder props(List<String> v) { props = v; return this; }
        public Builder hooks(List<String> v) { hooks = v; return this; }
        public Builder table(String v) { table = v; return this; }
        public Builder columns(List<String> v) { columns = v; return this; }

        private static <T> List<T> list(List<T> v) {
            return v == null ? List.of() : List.copyOf(v);
        }

        public CodeStructure build() {
            return new CodeStructure(packageName, className, extendsType, list(implementsTypes), list(annotations),
                    list(fields), list(methods), list(dependencies), list(exceptions), list(relatedTests), signature,
                    list(inputs), output, visibility, list(calls), list(calledBy), httpMethod, route, controller,
                    requestDto, responseDto, service, authorization, list(statusCodes), list(exports), list(imports),
                    list(props), list(hooks), table, list(columns));
        }
    }
}
