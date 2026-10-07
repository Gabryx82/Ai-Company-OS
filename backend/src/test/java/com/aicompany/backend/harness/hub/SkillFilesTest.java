package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.library.SkillDocument;
import com.aicompany.backend.harness.model.HarnessResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHASE 31 (ADR-036): reading the applications' SKILL.md, and what the Hub writes back. No Spring, no disk. */
class SkillFilesTest {

    @Test
    void theFrontmatterOfTheApplicationsIsReadWholeBlockScalarsQuotesAndOneLevelOfNesting() {
        String skill = """
                ---
                name: render-cli
                description: >-
                  Installs and uses the Render CLI
                  for deploys, logs and SSH.

                  Use it in CI.
                license: MIT
                compatibility: 'Render CLI v2.7.0+ (it''s fine)'
                metadata:
                  author: Render
                  version: "1.0.0"
                tags:
                  - deploy
                  - "cli"
                notes: |
                  first line
                    indented line
                ---

                # Render CLI
                """;
        Map<String, String> front = Frontmatter.read(skill);

        assertThat(front.get("name")).isEqualTo("render-cli");
        assertThat(front.get("description")).isEqualTo("Installs and uses the Render CLI for deploys, logs and SSH.\nUse it in CI.");
        assertThat(front.get("compatibility")).isEqualTo("Render CLI v2.7.0+ (it's fine)");
        assertThat(front.get("metadata.version")).isEqualTo("1.0.0");
        assertThat(front.get("metadata.author")).isEqualTo("Render");
        assertThat(front.get("tags")).isEqualTo("[deploy, cli]");
        assertThat(front.get("notes")).isEqualTo("first line\n  indented line");
        assertThat(Frontmatter.body(skill).strip()).isEqualTo("# Render CLI");
    }

    @Test
    void aDoubleQuotedDescriptionKeepsItsColonsAndEscapesAndAFileWithoutFrontmatterHasNone() {
        assertThat(Frontmatter.read("---\nname: x\ndescription: \"Use it: when \\\"asked\\\"\"\n---\n").get("description"))
                .isEqualTo("Use it: when \"asked\"");
        assertThat(Frontmatter.read("# Just markdown\n")).isEmpty();
        assertThat(Frontmatter.read("---\nname: never closed\n")).isEmpty();
    }

    @Test
    void theLibraryNowReadsAStandardSkillMdWithAFoldedDescriptionAndNoKey() {
        String standard = "---\nname: pdf\ndescription: |\n  Fill PDF forms.\n  Merge files.\nlicense: Proprietary\n---\n\n# PDF\n";
        SkillDocument document = SkillDocument.parse(standard, "pdf-anthropic");

        assertThat(document.key()).isEqualTo("pdf-anthropic");
        assertThat(document.name()).isEqualTo("pdf");
        assertThat(document.description()).isEqualTo("Fill PDF forms.\nMerge files.");
        assertThat(SkillDocument.declaresKey(standard)).isFalse();
        assertThat(SkillDocument.declaresKey("---\nkey: a-b\nname: A\n---\n")).isTrue();
    }

    @Test
    void packagePathsArePlainRelativeNamesThatStayInsideTheirFolder() {
        assertThat(PackagePaths.valid("SKILL.md")).isTrue();
        assertThat(PackagePaths.valid("scripts/fill_form.py")).isTrue();
        assertThat(PackagePaths.valid("riferimenti/città.md")).isTrue();
        for (String refused : List.of("../evil.md", "a/../../b", "/etc/passwd", "C:/Windows/x", "a\\b", "a//b", "con.txt",
                "scripts/nul", "trailing.", "")) {
            assertThat(PackagePaths.valid(refused)).as(refused).isFalse();
        }
        assertThat(PackagePaths.hidden(".git/config")).isTrue();
        assertThat(PackagePaths.hidden("docs/.env")).isTrue();
        assertThatThrownBy(() -> PackagePaths.resolve(Path.of("/tmp/skill"), "../x")).isInstanceOf(HubProblemException.class);
        assertThatThrownBy(() -> PackagePaths.requireBudget(PackagePaths.MAX_FILES + 1, 10)).isInstanceOf(HubProblemException.class);
        assertThatThrownBy(() -> PackagePaths.requireBudget(1, PackagePaths.MAX_TOTAL + 1)).isInstanceOf(HubProblemException.class);
    }

    @Test
    void aSkillGetsTheCategoryOfItsNameBeforeTheWordsOfItsDescription() {
        assertThat(SkillCategories.of("xlsx", null, "Use for spreadsheets").key()).isEqualTo("documenti");
        assertThat(SkillCategories.of("render-deploy", "render", "Deploy apps").key()).isEqualTo("devops");
        assertThat(SkillCategories.of("tilemap-palette-create", "unity", null).key()).isEqualTo("giochi-3d");
        assertThat(SkillCategories.of("call-prep", "sales", "Prepare a sales call").key()).isEqualTo("business");
        assertThat(SkillCategories.of("figma-use", "figma", null).key()).isEqualTo("design");
        // The name says nothing; the description does.
        assertThat(SkillCategories.of("helper", null, "Reviews a React component for re-renders").key()).isEqualTo("sviluppo");
        assertThat(SkillCategories.of("zzz", null, null)).isEqualTo(SkillCategories.OTHER);
    }

    @Test
    void aLibraryStyleSkillIsRenderedForTheApplicationsAndAStandardOneGoesAsItIs() {
        HarnessResource entry = new HarnessResource("api-review", HarnessResource.Kind.SKILL, "Revisione delle API REST",
                "Controlla verbi: codici \"giusti\"", List.of("api"), null, null, null);
        Map<String, byte[]> library = new TreeMap<>(Map.of(
                "SKILL.md", ("---\nkey: api-review\nname: Revisione delle API REST\nkind: SKILL\n"
                        + "description: Controlla verbi: codici \"giusti\"\ntags: [api]\n---\n\n# Regole\n").getBytes(StandardCharsets.UTF_8),
                "esempi/ok.http", "GET /x".getBytes(StandardCharsets.UTF_8)));

        Map<String, byte[]> rendered = SkillHubService.renderForApp(entry, library);
        String md = new String(rendered.get("SKILL.md"), StandardCharsets.UTF_8);
        Map<String, String> front = Frontmatter.read(md);
        assertThat(front.get("name")).isEqualTo("api-review");
        assertThat(front.get("description")).isEqualTo("Controlla verbi: codici \"giusti\"");
        assertThat(front.get("metadata.title")).isEqualTo("Revisione delle API REST");
        assertThat(front).doesNotContainKeys("key", "kind");
        assertThat(Frontmatter.body(md).strip()).isEqualTo("# Regole");
        assertThat(rendered).containsKey("esempi/ok.http");
        assertThat(SkillHubService.appCompatible(md)).isTrue();

        Map<String, byte[]> standard = Map.of("SKILL.md", "---\nname: pdf\ndescription: PDF\n---\n# PDF\n".getBytes(StandardCharsets.UTF_8));
        assertThat(SkillHubService.renderForApp(entry, standard).get("SKILL.md")).isEqualTo(standard.get("SKILL.md"));
    }

    @Test
    void aPackageWithoutAFrontmatterOrWithoutANameGetsOneAndHiddenFilesStayOut() {
        Map<String, byte[]> bare = new TreeMap<>(Map.of("SKILL.md", "# Accessibilità\n\nOgni input ha un'etichetta.\n"
                .getBytes(StandardCharsets.UTF_8), ".git/HEAD", "ref".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> library = SkillHubService.forLibrary("form-a11y", bare, "Accessibilità dei form", "Etichette");
        String md = new String(library.get("SKILL.md"), StandardCharsets.UTF_8);
        assertThat(Frontmatter.read(md)).containsEntry("name", "Accessibilità dei form").containsEntry("description", "Etichette");
        assertThat(library).doesNotContainKey(".git/HEAD");

        Map<String, byte[]> nameless = Map.of("SKILL.md", "---\ndescription: d\n---\nbody".getBytes(StandardCharsets.UTF_8));
        assertThat(Frontmatter.read(new String(SkillHubService.forLibrary("k1", nameless, "Nome", null).get("SKILL.md"),
                StandardCharsets.UTF_8))).containsEntry("name", "Nome").containsEntry("description", "d");

        assertThatThrownBy(() -> SkillHubService.forLibrary("k1", Map.of("README.md", new byte[1]), "x", null))
                .isInstanceOf(HubProblemException.class).hasMessageContaining("SKILL.md");
        assertThatThrownBy(() -> SkillHubService.forLibrary("k1", Map.of("SKILL.md", "---\nname: x\n---\n".getBytes(StandardCharsets.UTF_8),
                "../evil.sh", new byte[1]), "x", null)).isInstanceOf(HubProblemException.class);
    }

    @Test
    void aVersionIsNewerOnlyWhenItIsStrictlyLater() {
        assertThat(SkillUpdates.newer("0.2.3", "0.2.2")).isTrue();
        assertThat(SkillUpdates.newer("1.0.0", "0.9.9")).isTrue();
        assertThat(SkillUpdates.newer("0.1.6", "0.1.6-beta")).isTrue();
        assertThat(SkillUpdates.newer("0.2.2", "0.2.2")).isFalse();
        assertThat(SkillUpdates.newer("0.1.0", "0.1.12-5f7cd798dc99")).isFalse();
        assertThat(SkillUpdates.newer("v2.10.0", "2.9.1")).isTrue();
    }
}
