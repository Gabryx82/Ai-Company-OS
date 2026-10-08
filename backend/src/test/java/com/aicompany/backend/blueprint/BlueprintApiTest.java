package com.aicompany.backend.blueprint;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 28 (ADR-033): the Project Blueprint through its API -- the scan and its
 * reconciliation, manual blocks and paths, overrides, relations by origin, the
 * layout apart from the content, progressive expansion, proposed code changes
 * that become tasks, AI actions, and the invariant the whole design rests on:
 * nothing here ever writes a source file of the project.
 */
class BlueprintApiTest extends AbstractPostgresTest {

    private static final String PROBLEM = "urn:ai-company-os:problem:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long projectId;
    private String projectName;
    private String base;

    @BeforeEach
    void aProjectWithCode() throws Exception {
        projectName = "Blueprint " + System.nanoTime();
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + projectName + "\"}")).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        base = "/api/projects/" + projectId + "/blueprint";
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"FULL_STACK\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());

        String java = "server/src/main/java/com/acme/task/";
        write(java + "TaskController.java", """
                package com.acme.task;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api/tasks")
                public class TaskController {
                    private final TaskService service;

                    public TaskController(TaskService service) {
                        this.service = service;
                    }

                    @GetMapping("/{id}")
                    public Task one(@PathVariable Long id) {
                        return service.find(id);
                    }
                }
                """);
        write(java + "TaskService.java", """
                package com.acme.task;

                import org.springframework.stereotype.Service;

                /** Business rules of tasks. */
                @Service
                public class TaskService {
                    private final TaskRepository tasks;

                    public TaskService(TaskRepository tasks) {
                        this.tasks = tasks;
                    }

                    public Task find(Long id) {
                        if (id == null) {
                            throw new IllegalArgumentException("id");
                        }
                        return tasks.findById(id).orElseThrow();
                    }

                    public void archive(Task task) {
                        tasks.save(task);
                    }
                }
                """);
        write(java + "TaskRepository.java", """
                package com.acme.task;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface TaskRepository extends JpaRepository<Task, Long> {
                }
                """);
        write(java + "Task.java", """
                package com.acme.task;

                import jakarta.persistence.*;

                @Entity
                @Table(name = "tasks")
                public class Task {
                    @Id
                    private Long id;
                }
                """);
        write(java + "AuditLog.java", """
                package com.acme.task;

                public class AuditLog {
                    public void record(String what) {
                    }
                }
                """);
        write("server/src/main/resources/db/migration/V1__tasks.sql", "CREATE TABLE tasks (id BIGINT PRIMARY KEY);\n");
    }

    private void write(String relative, String content) throws Exception {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode graph() throws Exception {
        return body(mockMvc.perform(get(base)).andExpect(status().isOk()));
    }

    private JsonNode scan() throws Exception {
        return body(mockMvc.perform(post(base + "/scan")).andExpect(status().isOk()));
    }

    private JsonNode node(String key) throws Exception {
        for (JsonNode n : graph().path("nodes")) {
            if (n.path("key").asString().equals(key)) {
                return n;
            }
        }
        throw new AssertionError("no block " + key);
    }

    private long id(String key) throws Exception {
        return node(key).path("id").asLong();
    }

    private String tag(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode createNode(String bodyJson) throws Exception {
        return body(mockMvc.perform(post(base + "/nodes").contentType(MediaType.APPLICATION_JSON).content(bodyJson))
                .andExpect(status().isCreated()).andExpect(header().exists(HttpHeaders.ETAG)));
    }

    private JsonNode createEdge(long source, long target, String relation, String origin) throws Exception {
        return body(mockMvc.perform(post(base + "/edges").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":" + source + ",\"targetId\":" + target + ",\"relation\":\"" + relation
                        + "\",\"origin\":\"" + origin + "\"}")).andExpect(status().isCreated()));
    }

    /** Every file of the project except the context the scan writes for agents: path to SHA-256. */
    private Map<String, String> sourceFiles() throws Exception {
        Map<String, String> out = new TreeMap<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String rel = folder.relativize(f).toString().replace('\\', '/');
                if (!rel.startsWith(".aicos/")) {
                    out.put(rel, HexFormat.of().formatHex(sha.digest(Files.readAllBytes(f))));
                }
            }
        }
        return out;
    }

    // --- the scan -----------------------------------------------------------------

    @Test
    void anEmptyBlueprintIsReadableAndTheScanFillsIt() throws Exception {
        JsonNode empty = graph();
        assertThat(empty.path("nodes")).isEmpty();
        assertThat(empty.path("layout").path("version").asLong()).isZero();
        assertThat(empty.path("workspaceReady").asBoolean()).isTrue();

        JsonNode scan = scan();
        assertThat(scan.path("files").asInt()).isEqualTo(6);
        assertThat(scan.path("nodesAdded").asInt()).isEqualTo(scan.path("nodesDetected").asInt());
        assertThat(scan.path("languages").asString()).contains("java 5 (ast)").contains("sql 1 (lexical)");

        JsonNode g = graph();
        assertThat(g.path("metrics").path("endpoints").asInt()).isEqualTo(1);
        assertThat(g.path("metrics").path("databaseEntities").asInt()).isEqualTo(2);
        assertThat(g.path("metrics").path("detectedRelations").asInt()).isPositive();
        assertThat(g.path("lastScan").path("id").asLong()).isEqualTo(scan.path("id").asLong());
        // every block has a place on the canvas
        assertThat(g.path("layout").path("positions").size()).isEqualTo(g.path("nodes").size());
        String edges = g.path("edges").toString();
        long service = id("java:com.acme.task.TaskService");
        long repository = id("java:com.acme.task.TaskRepository");
        assertThat(edges).contains("\"source\":" + service + ",\"target\":" + repository
                + ",\"relation\":\"SERVICE_TO_REPOSITORY\"");
        assertThat(Files.readString(folder.resolve(".aicos/BLUEPRINT.md"))).contains("TaskService")
                .contains("service → repository TaskRepository");

        // PHASE 32 (TD-09): the history of scans, newest first.
        JsonNode second = scan();
        JsonNode scans = body(mockMvc.perform(get(base + "/scans")).andExpect(status().isOk()));
        assertThat(scans.size()).isEqualTo(2);
        assertThat(scans.path(0).path("id").asLong()).isEqualTo(second.path("id").asLong());
        assertThat(scans.path(1).path("id").asLong()).isEqualTo(scan.path("id").asLong());
        assertThat(scans.path(1).path("files").asInt()).isEqualTo(6);
    }

    @Test
    void aBlockIsReadWithItsStructureConnectionsAndCode() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        JsonNode detail = body(mockMvc.perform(get(base + "/nodes/" + service)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.startsWith("\"0."))));
        assertThat(detail.path("kind").asString()).isEqualTo("SERVICE");
        assertThat(detail.path("origin").asString()).isEqualTo("DETECTED");
        assertThat(detail.path("pathState").asString()).isEqualTo("LINKED");
        assertThat(detail.path("description").asString()).isEqualTo("Business rules of tasks.");
        assertThat(detail.path("structure").path("methods").size()).isEqualTo(2);
        assertThat(detail.path("pseudocode").asString()).contains("IF id == null").contains("REJECT IllegalArgumentException");
        assertThat(detail.path("incoming").toString()).contains("TaskController");
        assertThat(detail.path("outgoing").toString()).contains("TaskRepository");
        assertThat(detail.path("lastScanAt").isNull()).isFalse();

        JsonNode snippet = body(mockMvc.perform(get(base + "/nodes/" + service + "/snippet")).andExpect(status().isOk()));
        assertThat(snippet.path("startLine").asInt()).isEqualTo(5);
        assertThat(snippet.path("text").asString()).startsWith("/** Business rules of tasks. */").contains("tasks.save(task);");
        JsonNode whole = body(mockMvc.perform(get(base + "/nodes/" + service + "/snippet").param("full", "true")));
        assertThat(whole.path("text").asString()).startsWith("package com.acme.task;");
    }

    @Test
    void aSecondScanKeepsOverridesAndTheArrangementAndMarksWhatIsGone() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        String path = base + "/nodes/" + service;
        mockMvc.perform(put(path).header(HttpHeaders.IF_MATCH, tag(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE\",\"name\":\"Servizio task\",\"description\":\"Regole delle task.\","
                                + "\"pseudocode\":null,\"tags\":[\"core\",\"core\",\" \"],\"notes\":\"da rivedere\",\"status\":\"IMPLEMENTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.length()").value(3))
                .andExpect(jsonPath("$.tags.length()").value(1));
        // a moved block stays where it was put
        long layoutTag = graph().path("layout").path("version").asLong();
        mockMvc.perform(put(base + "/layout").header(HttpHeaders.IF_MATCH, "\"" + layoutTag + "\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"positions\":{\"" + service + "\":{\"x\":-999,\"y\":777}}}"))
                .andExpect(status().isOk());

        Files.delete(folder.resolve("server/src/main/java/com/acme/task/AuditLog.java"));
        write("server/src/main/java/com/acme/task/TaskEvents.java", "package com.acme.task;\npublic class TaskEvents {}\n");
        JsonNode second = scan();
        assertThat(second.path("nodesAdded").asInt()).isEqualTo(1);
        assertThat(second.path("nodesMissing").asInt()).isEqualTo(1);

        JsonNode detail = body(mockMvc.perform(get(path)));
        assertThat(detail.path("name").asString()).isEqualTo("Servizio task");
        assertThat(detail.path("description").asString()).isEqualTo("Regole delle task.");
        assertThat(detail.path("pseudocode").isNull()).isTrue();
        assertThat(detail.path("detected").path("name").asString()).isEqualTo("TaskService");
        assertThat(detail.path("notes").asString()).isEqualTo("da rivedere");
        JsonNode g = graph();
        assertThat(g.path("layout").path("positions").path(String.valueOf(service)).path("x").asDouble()).isEqualTo(-999);
        assertThat(node("java:com.acme.task.AuditLog").path("pathState").asString()).isEqualTo("MISSING");
        assertThat(g.path("metrics").path("missingNodes").asInt()).isEqualTo(1);

        // revert: the code's values come back, and the override is gone
        mockMvc.perform(post(path + "/revert").header(HttpHeaders.IF_MATCH, tag(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"name\",\"pseudocode\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TaskService"))
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0]").value("description"));
        // typing back the detected value is not an override
        JsonNode reverted = body(mockMvc.perform(get(path)));
        mockMvc.perform(put(path).header(HttpHeaders.IF_MATCH, tag(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE\",\"name\":\"TaskService\",\"description\":"
                                + json.writeValueAsString(reverted.path("detected").path("description").asString())
                                + ",\"pseudocode\":" + json.writeValueAsString(reverted.path("pseudocode").asString()) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.length()").value(0));
    }

    @Test
    void whatTheCodeAssertsIsNotEditedAwayHere() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        String path = base + "/nodes/" + service;
        mockMvc.perform(put(path).header(HttpHeaders.IF_MATCH, tag(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE\",\"name\":\"TaskService\",\"filePath\":\"elsewhere/X.java\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-derived-element"));
        mockMvc.perform(delete(path).header(HttpHeaders.IF_MATCH, tag(path)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-derived-element"));
        JsonNode detected = graph().path("edges").get(0);
        mockMvc.perform(delete(base + "/edges/" + detected.path("id").asLong()).header(HttpHeaders.IF_MATCH, "\"" + detected.path("version").asLong() + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-derived-element"));
    }

    @Test
    void everyMutationCarriesTheTagItRead() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        String path = base + "/nodes/" + service;
        String body = "{\"kind\":\"SERVICE\",\"name\":\"TaskService\"}";
        mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put(path).header(HttpHeaders.IF_MATCH, "\"41\"").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(put(base + "/layout").header(HttpHeaders.IF_MATCH, "\"41\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positions\":{}}"))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post(base + "/layout/auto")).andExpect(status().isPreconditionRequired());
    }

    @Test
    void movingABlockDoesNotInvalidateTheTagOfItsContent() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        String nodeTag = tag(base + "/nodes/" + service);
        long layout = graph().path("layout").path("version").asLong();
        JsonNode moved = body(mockMvc.perform(put(base + "/layout").header(HttpHeaders.IF_MATCH, "\"" + layout + "\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"positions\":{\"" + service + "\":{\"x\":10,\"y\":20},\"999999\":{\"x\":1,\"y\":1}}}"))
                .andExpect(status().isOk()));
        assertThat(moved.path("version").asLong()).isEqualTo(layout + 1);
        assertThat(moved.path("positions").has("999999")).as("a block of no project").isFalse();
        assertThat(tag(base + "/nodes/" + service)).isEqualTo(nodeTag);

        JsonNode reset = body(mockMvc.perform(post(base + "/layout/auto").header(HttpHeaders.IF_MATCH, "\"" + (layout + 1) + "\""))
                .andExpect(status().isOk()));
        assertThat(reset.path("positions").path(String.valueOf(service)).path("x").asDouble()).isNotEqualTo(10);
    }

    // --- manual blocks and their paths --------------------------------------------

    @Test
    void aManualBlockLinksToARealFileAndOtherwiseSaysWhatItsPathIs() throws Exception {
        JsonNode linked = createNode("{\"kind\":\"SERVICE\",\"name\":\"Audit\",\"filePath\":\"./server/src/main/java/com/acme/task/AuditLog.java\","
                + "\"pathState\":\"TO_CREATE\",\"tags\":[\"audit\"]}");
        assertThat(linked.path("pathState").asString()).isEqualTo("LINKED");
        assertThat(linked.path("filePath").asString()).isEqualTo("server/src/main/java/com/acme/task/AuditLog.java");
        assertThat(linked.path("language").asString()).isEqualTo("java");
        assertThat(linked.path("origin").asString()).isEqualTo("MANUAL");
        assertThat(linked.path("status").asString()).isEqualTo("PLANNED");

        assertThat(createNode("{\"kind\":\"DOCUMENT\",\"name\":\"Spec\",\"filePath\":\"docs/spec.md\"}").path("pathState").asString())
                .isEqualTo("DOCUMENT_REFERENCE");
        assertThat(createNode("{\"kind\":\"CUSTOM\",\"name\":\"Idea\"}").path("pathState").asString()).isEqualTo("VIRTUAL");
        JsonNode toCreate = createNode("{\"kind\":\"SERVICE\",\"name\":\"Notifier\",\"filePath\":\"server/src/main/java/com/acme/task/Notifier.java\","
                + "\"pathState\":\"TO_CREATE\",\"x\":100,\"y\":200}");
        assertThat(toCreate.path("pathState").asString()).isEqualTo("TO_CREATE");
        assertThat(graph().path("layout").path("positions").path(toCreate.path("id").asString()).path("y").asDouble()).isEqualTo(200);
        // the server does not create it
        assertThat(folder.resolve("server/src/main/java/com/acme/task/Notifier.java")).doesNotExist();

        mockMvc.perform(post(base + "/nodes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE\",\"name\":\"Ghost\",\"filePath\":\"nowhere/Ghost.java\",\"pathState\":\"LINKED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pathState").exists());
        mockMvc.perform(post(base + "/nodes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE\",\"name\":\"Out\",\"filePath\":\"../outside.java\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base + "/nodes").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"No kind\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind").exists());

        // the file appears: the next scan links the block
        write("server/src/main/java/com/acme/task/Notifier.java", "package com.acme.task;\npublic class Notifier {}\n");
        scan();
        assertThat(body(mockMvc.perform(get(base + "/nodes/" + toCreate.path("id").asLong()))).path("pathState").asString())
                .isEqualTo("LINKED");
    }

    @Test
    void aManualBlockCarriesItsOwnStructureAndPseudocodeSkeleton() throws Exception {
        JsonNode endpoint = createNode("{\"kind\":\"ENDPOINT\",\"name\":\"POST /api/tasks\",\"structure\":{\"httpMethod\":\"POST\","
                + "\"route\":\"/api/tasks\",\"requestDto\":\"TaskRequest\",\"service\":\"TaskService\",\"statusCodes\":[\"201\"]}}");
        assertThat(endpoint.path("section").asString()).isEqualTo("ENDPOINT");
        assertThat(endpoint.path("structure").path("statusCodes").get(0).asString()).isEqualTo("201");
        assertThat(endpoint.path("structure").path("methods").isArray()).isTrue();
        String path = base + "/nodes/" + endpoint.path("id").asLong();
        mockMvc.perform(post(path + "/pseudocode").header(HttpHeaders.IF_MATCH, tag(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pseudocode").value("ON POST /api/tasks\n  VALIDATE TaskRequest\n  CALL TaskService\n  RETURN response"));
        mockMvc.perform(delete(path).header(HttpHeaders.IF_MATCH, tag(path))).andExpect(status().isNoContent());
        mockMvc.perform(get(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-node-not-found"));
    }

    @Test
    void anArchivedProjectIsReadButNotWritten() throws Exception {
        scan();
        String project = "/api/projects/" + projectId;
        mockMvc.perform(post(project + "/archive").header(HttpHeaders.IF_MATCH, tag(project))).andExpect(status().isOk());
        mockMvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(post(base + "/nodes").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"CUSTOM\",\"name\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "archived-project-is-immutable"));
        mockMvc.perform(post(base + "/scan")).andExpect(status().isConflict());
        mockMvc.perform(get("/api/projects/424242/blueprint")).andExpect(status().isNotFound());
    }

    // --- relations ----------------------------------------------------------------

    @Test
    void relationsAreDrawnProposedConfirmedAndKeptAcrossScans() throws Exception {
        scan();
        long controller = id("java:com.acme.task.TaskController");
        long audit = id("java:com.acme.task.AuditLog");
        long task = id("java:com.acme.task.Task");
        JsonNode manual = createEdge(controller, audit, "USES", "MANUAL");
        assertThat(manual.path("origin").asString()).isEqualTo("MANUAL");
        assertThat(manual.path("family").asString()).isEqualTo("DEPENDENCY");
        mockMvc.perform(post(base + "/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + controller + ",\"targetId\":" + audit + ",\"relation\":\"USES\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-relation-exists"));
        mockMvc.perform(post(base + "/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + controller + ",\"targetId\":" + controller + ",\"relation\":\"USES\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base + "/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + controller + ",\"targetId\":" + audit + ",\"relation\":\"CALLS\",\"origin\":\"DETECTED\"}"))
                .andExpect(status().isBadRequest());

        JsonNode proposed = createEdge(audit, task, "READS", "PROPOSED");
        String edgePath = base + "/edges/" + proposed.path("id").asLong();
        mockMvc.perform(put(edgePath).header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relation\":\"WRITES\",\"label\":\"audit trail\",\"description\":\"scrive una riga per modifica\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relation").value("WRITES"));
        mockMvc.perform(post(edgePath + "/confirm").header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("MANUAL"));
        mockMvc.perform(post(edgePath + "/confirm").header(HttpHeaders.IF_MATCH, "\"2\"")).andExpect(status().isBadRequest());

        // the code starts calling AuditLog from the service: a proposal of that becomes a fact
        JsonNode proposal = createEdge(id("java:com.acme.task.TaskService"), audit, "CALLS", "PROPOSED");
        write("server/src/main/java/com/acme/task/TaskService.java", Files.readString(folder.resolve(
                "server/src/main/java/com/acme/task/TaskService.java")).replace("private final TaskRepository tasks;",
                "private final TaskRepository tasks;\n    private final AuditLog audit = new AuditLog();")
                .replace("tasks.save(task);", "tasks.save(task);\n        audit.record(\"archived\");"));
        scan();
        String edges = graph().path("edges").toString();
        assertThat(edges).contains("\"id\":" + manual.path("id").asLong() + ",")
                .contains("\"id\":" + proposed.path("id").asLong() + ",")
                .contains("\"id\":" + proposal.path("id").asLong() + ",\"source\":" + id("java:com.acme.task.TaskService")
                        + ",\"target\":" + audit + ",\"relation\":\"CALLS\",\"family\":\"DEPENDENCY\",\"origin\":\"DETECTED\"");
        assertThat(graph().path("metrics").path("manualRelations").asInt()).isEqualTo(2);

        // a detected block is not deleted while the code still has it
        mockMvc.perform(delete(base + "/nodes/" + audit).header(HttpHeaders.IF_MATCH, tag(base + "/nodes/" + audit)))
                .andExpect(status().isConflict());
    }

    // --- progressive expansion ------------------------------------------------------

    @Test
    void aClassExpandsIntoItsMethodsAndStaysExpandedAcrossScans() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        String path = base + "/nodes/" + service;
        mockMvc.perform(post(path + "/expand").header(HttpHeaders.IF_MATCH, tag(path)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expanded").value(true));
        JsonNode find = node("java:com.acme.task.TaskService#find(Long)");
        assertThat(find.path("kind").asString()).isEqualTo("FUNCTION");
        assertThat(find.path("parentId").asLong()).isEqualTo(service);
        long findId = find.path("id").asLong();
        String edges = graph().path("edges").toString();
        assertThat(edges).contains("\"source\":" + service + ",\"target\":" + findId + ",\"relation\":\"CONTAINS\"")
                .contains("\"source\":" + findId + ",\"target\":" + id("java:com.acme.task.TaskRepository") + ",\"relation\":\"CALLS\"");
        JsonNode method = body(mockMvc.perform(get(base + "/nodes/" + findId)));
        assertThat(method.path("structure").path("signature").asString()).isEqualTo("Task find(Long id)");
        assertThat(method.path("pseudocode").asString()).startsWith("IF id == null");

        scan();
        assertThat(node("java:com.acme.task.TaskService#find(Long)").path("id").asLong()).isEqualTo(findId);
        assertThat(graph().path("edges").toString()).contains("\"source\":" + service + ",\"target\":" + findId + ",\"relation\":\"CONTAINS\"");

        mockMvc.perform(post(path + "/collapse").header(HttpHeaders.IF_MATCH, tag(path))).andExpect(status().isOk());
        assertThat(graph().path("nodes").toString()).doesNotContain("TaskService#find");
        mockMvc.perform(post(base + "/nodes/" + id("table:tasks") + "/expand").header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isBadRequest());
    }

    // --- proposed code changes and AI actions ------------------------------------------

    @Test
    void codeChangesAreProposedAsDiffsAndBecomeTasksAndNoSourceFileIsEverWritten() throws Exception {
        scan();
        Map<String, String> before = sourceFiles();
        long controller = id("java:com.acme.task.TaskController");
        long audit = id("java:com.acme.task.AuditLog");
        JsonNode edge = createEdge(controller, audit, "USES", "MANUAL");

        JsonNode dependency = body(mockMvc.perform(post(base + "/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ADD_DEPENDENCY\",\"edgeId\":" + edge.path("id").asLong() + "}")).andExpect(status().isCreated()));
        String diff = dependency.path("diff").asString();
        assertThat(diff).startsWith("--- a/server/src/main/java/com/acme/task/TaskController.java\n+++ b/server/src/main/java/com/acme/task/TaskController.java\n@@ ")
                .contains("+    private final AuditLog auditLog;")
                .contains("-    public TaskController(TaskService service) {")
                .contains("+    public TaskController(TaskService service, AuditLog auditLog) {")
                .contains("+        this.auditLog = auditLog;")
                .contains("     @GetMapping(\"/{id}\")");
        assertThat(dependency.path("status").asString()).isEqualTo("PROPOSED");
        assertThat(dependency.path("consequences").asString()).contains("costruttore");

        JsonNode designed = createNode("{\"kind\":\"SERVICE\",\"name\":\"Notifier\",\"filePath\":\"server/src/main/java/com/acme/task/Notifier.java\","
                + "\"pathState\":\"TO_CREATE\",\"description\":\"Avvisa l'operatore.\",\"pseudocode\":\"ON task archived\\n  SEND mail\"}");
        JsonNode creation = body(mockMvc.perform(post(base + "/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"CREATE_FILE\",\"nodeId\":" + designed.path("id").asLong() + "}")).andExpect(status().isCreated()));
        assertThat(creation.path("diff").asString()).startsWith("--- /dev/null\n+++ b/server/src/main/java/com/acme/task/Notifier.java\n@@ -0,0 +1,")
                .contains("+package com.acme.task;").contains("+ * Avvisa l'operatore.").contains("+ * ON task archived")
                .contains("+@org.springframework.stereotype.Service").contains("+public class Notifier {");
        mockMvc.perform(post(base + "/proposals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CREATE_FILE\",\"nodeId\":" + controller + "}"))
                .andExpect(status().isBadRequest());

        // accepting creates the task that carries the change; it applies nothing
        String proposalPath = base + "/proposals/" + dependency.path("id").asLong();
        mockMvc.perform(post(proposalPath + "/accept")).andExpect(status().isPreconditionRequired());
        JsonNode accepted = body(mockMvc.perform(post(proposalPath + "/accept").header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk()));
        assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");
        long taskId = accepted.path("taskId").asLong();
        JsonNode task = body(mockMvc.perform(get("/api/tasks/" + taskId)).andExpect(status().isOk()));
        assertThat(task.path("title").asString()).isEqualTo("Modifica al codice: Inietta AuditLog in TaskController");
        assertThat(task.path("projectId").asLong()).isEqualTo(projectId);
        assertThat(task.path("status").asString()).isEqualTo("OPEN");
        assertThat(task.path("description").asString()).contains("```diff").contains("+    private final AuditLog auditLog;");
        mockMvc.perform(post(proposalPath + "/accept").header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "blueprint-proposal-decided"));
        mockMvc.perform(post(base + "/proposals/" + creation.path("id").asLong() + "/reject").header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(body(mockMvc.perform(get(base + "/proposals"))).size()).isEqualTo(2);

        JsonNode custom = body(mockMvc.perform(post(base + "/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"CUSTOM\",\"filePath\":\"README.md\",\"title\":\"Documenta il Blueprint\",\"diff\":\"+ una riga\"}"))
                .andExpect(status().isCreated()));
        assertThat(custom.path("diff").asString()).isEqualTo("+ una riga\n");

        // the invariant: every operation above left the project's files as they were
        assertThat(sourceFiles()).isEqualTo(before);
    }

    @Test
    void anAiActionIsATaskWithTheComponentsContext() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        JsonNode outcome = body(mockMvc.perform(post(base + "/nodes/" + service + "/ai-actions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"EXPLAIN_COMPONENT\"}")).andExpect(status().isCreated()));
        JsonNode task = body(mockMvc.perform(get("/api/tasks/" + outcome.path("taskId").asLong())));
        assertThat(task.path("title").asString()).isEqualTo("Spiega il componente: TaskService");
        assertThat(task.path("projectId").asLong()).isEqualTo(projectId);
        assertThat(task.path("description").asString()).contains("server/src/main/java/com/acme/task/TaskService.java")
                .contains("È usato da: ").contains("TaskController (").contains("Non modificare il codice");
        mockMvc.perform(post(base + "/nodes/" + service + "/ai-actions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"DANCE\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void theVocabularyIsTheServers() throws Exception {
        mockMvc.perform(get("/api/blueprint/vocabulary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kinds.length()").value(21))
                .andExpect(jsonPath("$.relations.length()").value(19))
                .andExpect(jsonPath("$.actions.length()").value(8))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'CONTROLLER')].family").value("APIS"))
                .andExpect(jsonPath("$.relations[?(@.relation == 'TEST_COVERS')].family").value("TESTS"));
    }

    // --- deleting the project -------------------------------------------------------------

    @Test
    void deletingAProjectForRealDeletesItsBlueprint() throws Exception {
        scan();
        long service = id("java:com.acme.task.TaskService");
        JsonNode edge = createEdge(id("java:com.acme.task.TaskController"), id("java:com.acme.task.AuditLog"), "USES", "MANUAL");
        JsonNode proposal = body(mockMvc.perform(post(base + "/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ADD_DEPENDENCY\",\"edgeId\":" + edge.path("id").asLong() + "}")));
        mockMvc.perform(post(base + "/proposals/" + proposal.path("id").asLong() + "/accept").header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        assertThat(service).isPositive();
        String admin = AdminSession.bearer(mockMvc);
        String project = "/api/projects/" + projectId;
        mockMvc.perform(get(project + "/deletion").header(HttpHeaders.AUTHORIZATION, admin).param("tasks", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[?(@ =~ /.*Blueprint.*/)]").exists());
        mockMvc.perform(delete(project).header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, tag(project))
                        .param("tasks", "DELETE").param("confirm", projectName))
                .andExpect(status().isOk());
        for (String table : new String[]{"blueprint_nodes", "blueprint_edges", "blueprint_scans", "blueprint_layouts",
                "blueprint_change_proposals"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, projectId))
                    .as(table).isZero();
        }
    }
    /**
     * PHASE 30 (ADR-035 §8): the whole Blueprint is deleted from the console -- an
     * admin's decision, with the project's name typed back, under the layout's tag
     * the canvas holds. The code is never touched, and a new scan rebuilds what the
     * code says.
     */
    @Test
    void theWholeBlueprintIsDeletedByAnAdminAndTheCodeIsUntouched() throws Exception {
        scan();
        createNode("{\"kind\":\"SERVICE\",\"name\":\"Disegnato a mano\"}");
        Path code = folder.resolve("server/src/main/java/com/acme/task/TaskService.java");
        byte[] before = Files.readAllBytes(code);
        String admin = com.aicompany.backend.support.AdminSession.bearer(mockMvc);
        String layoutTag = "\"" + graph().path("layout").path("version").asLong() + "\"";

        mockMvc.perform(get(base + "/deletion"))
                .andExpect(jsonPath("$.kind").value("BLUEPRINT"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.deletes[0]").value(org.hamcrest.Matchers.containsString("1 disegnati a mano")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)
                        .header(HttpHeaders.IF_MATCH, layoutTag).param("confirm", projectName))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)
                        .header(HttpHeaders.AUTHORIZATION, admin).param("confirm", projectName))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, layoutTag).param("confirm", "no"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:delete-confirmation-mismatch"));
        assertThat(graph().path("nodes").size()).isPositive();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, layoutTag)
                        .param("confirm", projectName))
                .andExpect(status().isNoContent());

        JsonNode empty = graph();
        assertThat(empty.path("nodes").size()).isZero();
        assertThat(empty.path("edges").size()).isZero();
        assertThat(empty.path("lastScan").isNull() || empty.path("lastScan").isMissingNode()).isTrue();
        assertThat(Files.readAllBytes(code)).as("the code is never touched").isEqualTo(before);

        // A scan rebuilds what the code asserts; the hand-drawn block does not come back.
        scan();
        assertThat(graph().path("nodes").size()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blueprint_nodes WHERE project_id = ? AND origin = 'MANUAL'",
                Integer.class, projectId)).isZero();
    }
}
