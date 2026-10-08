package com.aicompany.backend.blueprint;

import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Component;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 28 (ADR-033): the scanner on real code -- this repository. A fixture
 * proves the rules; this proves they hold on a few hundred files nobody wrote for
 * a test, in reasonable time. Like {@code DebtRegistryConsistencyTest}, it reads
 * outside its module, and fails rather than skipping when it cannot find it.
 */
class BlueprintSelfScanTest {

    private static BlueprintScanner.Result result;
    private static Duration took;

    @BeforeAll
    static void scanThisRepository() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertThat(Files.isDirectory(root.resolve("backend/src/main/java")))
                .as("the repository root, seen from backend/").isTrue();
        Instant start = Instant.now();
        result = BlueprintScanner.withDefaultPlugins().scan(root);
        took = Duration.between(start, Instant.now());
        Map<NodeKind, Long> kinds = result.components().stream()
                .collect(Collectors.groupingBy(Component::kind, TreeMap::new, Collectors.counting()));
        Map<RelationType, Long> relations = result.relations().stream()
                .collect(Collectors.groupingBy(BlueprintScanner.Relation::relation, TreeMap::new, Collectors.counting()));
        System.out.printf("Blueprint self-scan: %d files, %d components %s, %d relations %s, %d unresolved, %s, %d ms%n",
                result.files(), result.components().size(), kinds, result.relations().size(), relations,
                result.unresolved(), result.languages(), took.toMillis());
        result.unresolvedSamples().forEach(u -> System.out.println("  unresolved: " + u));
    }

    private static boolean has(String source, RelationType type, String target) {
        return result.relations().stream().anyMatch(r -> r.sourceKey().equals(source) && r.relation() == type
                && r.targetKey().equals(target));
    }

    @Test
    void theControlPlaneReadsAsControllersServicesAndRepositories() {
        String controller = "java:com.aicompany.backend.task.controller.TaskController";
        String service = "java:com.aicompany.backend.task.service.TaskService";
        String repository = "java:com.aicompany.backend.task.repository.TaskRepository";
        assertThat(result.components()).filteredOn(c -> c.key().equals(controller)).extracting(Component::kind)
                .containsExactly(NodeKind.CONTROLLER);
        assertThat(has(controller, RelationType.CALLS, service)).isTrue();
        assertThat(has(service, RelationType.SERVICE_TO_REPOSITORY, repository)).isTrue();
        assertThat(has(repository, RelationType.MAPS_TO, "java:com.aicompany.backend.task.model.Task")).isTrue();
        assertThat(has("java:com.aicompany.backend.task.model.Task", RelationType.ENTITY_TO_TABLE, "table:tasks")).isTrue();
    }

    @Test
    void theBlueprintFindsItsOwnEndpointAndTheConsoleThatCallsIt() {
        String endpoint = "endpoint:com.aicompany.backend.blueprint.controller.BlueprintController#blueprintGraph:GET /api/projects/{id}/blueprint";
        assertThat(result.components()).extracting(Component::key).contains(endpoint);
        assertThat(has(endpoint, RelationType.BELONGS_TO, "java:com.aicompany.backend.blueprint.controller.BlueprintController")).isTrue();
        assertThat(result.components()).filteredOn(c -> c.key().equals("ts:frontend/src/views/ProjectDetail.tsx"))
                .extracting(Component::kind).containsExactly(NodeKind.COMPONENT);
    }

    /** PHASE 32 (TD-62): a delete that only SecurityConfiguration guards reads as an admin's. */
    @Test
    void theRulesByPathOfTheSecurityConfigurationReachTheEndpoints() {
        String delete = "endpoint:com.aicompany.backend.deletion.DeletionController#deleteTask:DELETE /api/tasks/{id}";
        assertThat(result.components()).filteredOn(c -> c.key().equals(delete)).singleElement()
                .satisfies(c -> assertThat(c.structure().authorization())
                        .startsWith("hasRole('ADMIN') — regola per percorso DELETE /api/tasks/*")
                        .contains("SecurityConfiguration.java:"));
        String login = "endpoint:com.aicompany.backend.user.controller.AuthController#login:POST /api/auth/login";
        assertThat(result.components()).filteredOn(c -> c.key().equals(login)).singleElement()
                .satisfies(c -> assertThat(c.structure().authorization()).startsWith("permitAll()"));
    }

    @Test
    void itIsFastAndResolvesAlmostEverything() {
        assertThat(result.files()).isGreaterThan(300);
        assertThat(took).isLessThan(Duration.ofSeconds(60));
        assertThat(result.unresolved()).isLessThan(result.relations().size() / 50 + 5);
    }
}
