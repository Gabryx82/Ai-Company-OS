package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.FileAnalysis;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PHASE 32 (TD-57): TypeScript, JavaScript and Python are parsed by their own
 * parsers when the runtimes are on the machine -- so what is in a comment or a
 * string is not an import -- and read with patterns when they are not.
 */
class ExternalAstTest {

    private static final SourceFile TSX = new SourceFile("web/src/Tasks.tsx", """
            // import { Old } from './Old';
            import { api } from './api/client';
            const help = "import { Ghost } from './Ghost'";
            export function Tasks({ project, onOpen }: { project: number; onOpen: () => void }) {
              const [n] = useState(0);
              useEffect(() => { api.call("GET", `/api/projects/${project}/tasks?all=1`); }, []);
              return <div>{n}</div>;
            }
            """);

    private static final SourceFile PY = new SourceFile("engine/app/main.py", """
            \"\"\"The engine.

                from app.secret import key
            \"\"\"
            from fastapi import APIRouter
            from . import util
            router = APIRouter(prefix="/v1")

            @router.post("/completions")
            async def complete(request):
                return 1
            """);

    @Test
    void typeScriptsParserSeesNoImportInACommentOrAString() {
        TypeScriptPlugin plugin = new TypeScriptPlugin();
        Object parsed = plugin.prepare(List.of(TSX));
        assumeTrue(parsed != null, "Node.js and TypeScript's compiler are not on this machine");
        assertThat(plugin.fidelity(parsed)).isEqualTo("ast");

        FileAnalysis ast = plugin.analyze(TSX, parsed);
        assertThat(imports(ast)).containsExactly("./api/client");
        assertThat(ast.components().getFirst().structure().props()).containsExactly("project", "onOpen");
        assertThat(ast.components().getFirst().structure().hooks()).containsExactly("useState", "useEffect");
        assertThat(ast.references()).anyMatch(r -> r.relation() == RelationType.CALLS
                && r.targetCandidates().equals(List.of("route:GET /api/projects/{}/tasks")));

        // The patterns, by contrast, take the commented-out line for an import.
        FileAnalysis lexical = plugin.analyze(TSX, null);
        assertThat(plugin.fidelity(null)).isEqualTo("lexical");
        assertThat(imports(lexical)).contains("./Old");
    }

    @Test
    void pythonsParserSeesNoImportInADocstring() {
        PythonPlugin plugin = new PythonPlugin();
        Object parsed = plugin.prepare(List.of(PY));
        assumeTrue(parsed != null, "No Python interpreter on this machine");
        assertThat(plugin.fidelity(parsed)).isEqualTo("ast");

        FileAnalysis ast = plugin.analyze(PY, parsed);
        assertThat(ast.components().getFirst().structure().imports()).containsExactly("fastapi", ".");
        assertThat(ast.components()).anyMatch(c -> c.name().equals("POST /v1/completions"));

        assertThat(plugin.analyze(PY, null).components().getFirst().structure().imports()).contains("app.secret");
    }

    @Test
    void withoutTheRuntimeTheFilesAreReadWithPatterns() {
        assertThat(ExternalAst.run(List.of("aicos-no-such-runtime"), "py-ast.py", Map.of(), List.of(PY))).isEmpty();
    }

    private static List<String> imports(FileAnalysis analysis) {
        return analysis.components().getFirst().structure().imports();
    }
}
