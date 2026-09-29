package com.aicompany.backend.blueprint;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Component;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 28 (ADR-033 §4-5): code into components and relations, without a
 * database. The fixture is the directive's own example -- TaskController uses
 * TaskService, which implements TaskOperations and calls TaskRepository, which
 * maps the Task entity, which is the table {@code tasks} -- plus a React client
 * that calls the API, a FastAPI engine and the tests that cover them.
 */
class BlueprintScannerTest {

    private static BlueprintScanner.Result result;

    @BeforeAll
    static void scanTheFixture() {
        List<SourceFile> files = new ArrayList<>();
        String java = "server/src/main/java/com/acme/task/";
        files.add(new SourceFile(java + "TaskController.java", """
                package com.acme.task;

                import com.acme.task.dto.TaskRequest;
                import com.acme.task.Missing;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;
                import jakarta.validation.Valid;
                import java.net.URI;
                import java.util.List;

                /** The REST face of tasks. More prose here. */
                @RestController
                @RequestMapping("/api/tasks")
                public class TaskController {

                    private final TaskService taskService;
                    private final TaskMapper mapper;
                    private Missing ghost;

                    public TaskController(TaskService taskService, TaskMapper mapper) {
                        this.taskService = taskService;
                        this.mapper = mapper;
                    }

                    @GetMapping
                    public List<TaskDto> list() {
                        return taskService.findAll().stream().map(mapper::toDto).toList();
                    }

                    @PostMapping
                    public ResponseEntity<TaskDto> create(@Valid @RequestBody TaskRequest request) {
                        Task task = taskService.create(request.title(), request.projectId());
                        return ResponseEntity.created(URI.create("/api/tasks/" + task.getId())).body(mapper.toDto(task));
                    }

                    @GetMapping("/{id}")
                    public TaskDto one(@PathVariable Long id) {
                        return mapper.toDto(taskService.find(id));
                    }
                }
                """));
        files.add(new SourceFile(java + "TaskService.java", """
                package com.acme.task;

                import com.acme.project.Project;
                import com.acme.project.ProjectRepository;
                import org.springframework.stereotype.Service;
                import java.util.List;

                @Service
                public class TaskService implements TaskOperations {

                    private final TaskRepository tasks;
                    private final ProjectRepository projects;

                    public TaskService(TaskRepository tasks, ProjectRepository projects) {
                        this.tasks = tasks;
                        this.projects = projects;
                    }

                    public List<Task> findAll() {
                        return tasks.findAll();
                    }

                    public Task find(Long id) {
                        return tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
                    }

                    public Task create(String title, Long projectId) {
                        Project project = projects.findById(projectId).orElseThrow();
                        if (project.isArchived()) {
                            throw new ArchivedProjectException(projectId);
                        }
                        Task task = new Task(title, project);
                        tasks.save(task);
                        return task;
                    }
                }
                """));
        files.add(new SourceFile(java + "TaskOperations.java", """
                package com.acme.task;

                public interface TaskOperations {
                    Task create(String title, Long projectId);
                }
                """));
        files.add(new SourceFile(java + "TaskRepository.java", """
                package com.acme.task;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface TaskRepository extends JpaRepository<Task, Long> {
                }
                """));
        files.add(new SourceFile(java + "Task.java", """
                package com.acme.task;

                import com.acme.project.Project;
                import jakarta.persistence.*;

                @Entity
                @Table(name = "tasks")
                public class Task {
                    @Id
                    private Long id;
                    @Column(name = "title")
                    private String title;
                    @ManyToOne
                    @JoinColumn(name = "project_id")
                    private Project project;

                    protected Task() {
                    }

                    public Task(String title, Project project) {
                        this.title = title;
                        this.project = project;
                    }

                    public Long getId() { return id; }
                }
                """));
        files.add(new SourceFile(java + "TaskDto.java", """
                package com.acme.task;

                public record TaskDto(Long id, String title) {
                }
                """));
        files.add(new SourceFile(java + "dto/TaskRequest.java", """
                package com.acme.task.dto;

                public record TaskRequest(String title, Long projectId) {
                }
                """));
        files.add(new SourceFile(java + "TaskMapper.java", """
                package com.acme.task;

                import org.springframework.stereotype.Component;

                @Component
                public class TaskMapper {
                    public TaskDto toDto(Task task) {
                        return new TaskDto(task.getId(), null);
                    }
                }
                """));
        files.add(new SourceFile("server/src/main/java/com/acme/project/Project.java", """
                package com.acme.project;

                import jakarta.persistence.Entity;

                @Entity
                public class Project {
                    private boolean archived;
                    public boolean isArchived() { return archived; }
                }
                """));
        files.add(new SourceFile("server/src/main/java/com/acme/project/ProjectRepository.java", """
                package com.acme.project;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface ProjectRepository extends JpaRepository<Project, Long> {
                }
                """));
        files.add(new SourceFile("server/src/test/java/com/acme/task/TaskApiTest.java", """
                package com.acme.task;

                import org.junit.jupiter.api.Test;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

                class TaskApiTest {
                    @Test
                    void readsOne() throws Exception {
                        long id = 4;
                        mockMvc.perform(get("/api/tasks/" + id));
                        mockMvc.perform(post("/api/tasks").content("{}"));
                    }
                }
                """));
        files.add(new SourceFile("server/src/main/resources/db/migration/V1__create_tasks.sql", """
                -- the first tables
                CREATE TABLE projects (
                    id BIGINT PRIMARY KEY,
                    archived BOOLEAN NOT NULL DEFAULT FALSE
                );
                CREATE TABLE tasks (
                    id         BIGINT PRIMARY KEY,
                    title      VARCHAR(255) NOT NULL,
                    project_id BIGINT REFERENCES projects (id),
                    CONSTRAINT tasks_title_check CHECK (length(title) > 0)
                );
                """));
        files.add(new SourceFile("server/src/main/resources/db/migration/V2__add_priority.sql", """
                ALTER TABLE tasks ADD COLUMN priority VARCHAR(16);
                """));
        files.add(new SourceFile("web/src/App.tsx", """
                // The console's root: the list of tasks.
                import { useTasks } from "./hooks/useTasks";
                import { TaskList } from "./TaskList";

                export function App({ title, compact }: { title: string; compact?: boolean }) {
                  const tasks = useTasks();
                  if (!tasks) return null;
                  return <TaskList tasks={tasks} />;
                }
                """));
        files.add(new SourceFile("web/src/TaskList.tsx", """
                export function TaskList({ tasks }: { tasks: string[] }) {
                  return <ul>{tasks.map((t) => <li key={t}>{t}</li>)}</ul>;
                }
                """));
        files.add(new SourceFile("web/src/hooks/useTasks.ts", """
                import { useEffect, useState } from "react";
                import { api } from "../api/client";

                export function useTasks() {
                  const [tasks, setTasks] = useState<string[] | null>(null);
                  useEffect(() => { api.task(1).then(setTasks); }, []);
                  return tasks;
                }
                """));
        files.add(new SourceFile("web/src/api/client.ts", """
                export class Client {
                  task(id: number) {
                    return this.plain("GET", `/api/tasks/${id}`);
                  }
                  create() {
                    return this.plain("POST", "/api/tasks");
                  }
                }
                export const api = new Client();
                """));
        files.add(new SourceFile("web/src/App.test.tsx", """
                import { App } from "./App";
                it("renders", () => { expect(App).toBeDefined(); });
                """));
        files.add(new SourceFile("engine/app/main.py", """
                \"\"\"The engine's HTTP face.\"\"\"
                from fastapi import FastAPI
                from app.models import Completion

                app = FastAPI()

                @app.post("/v1/completions")
                async def complete(request: Completion):
                    if not request.prompt:
                        raise ValueError("empty")
                    return {"ok": True}
                """));
        files.add(new SourceFile("engine/app/models.py", """
                from pydantic import BaseModel

                class Completion(BaseModel):
                    prompt: str
                """));
        result = BlueprintScanner.withDefaultPlugins().analyze(files, false);
    }

    private static Component component(String key) {
        return result.components().stream().filter(c -> c.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no component " + key + " in " + result.components().stream().map(Component::key).toList()));
    }

    private static Optional<BlueprintScanner.Relation> relation(String source, RelationType type, String target) {
        return result.relations().stream()
                .filter(r -> r.sourceKey().equals(source) && r.relation() == type && r.targetKey().equals(target))
                .findFirst();
    }

    private static void assertRelation(String source, RelationType type, String target) {
        assertThat(relation(source, type, target))
                .as("%s %s %s among %s", source, type, target, result.relations().stream()
                        .filter(r -> r.sourceKey().equals(source)).map(r -> r.relation() + "->" + r.targetKey()).toList())
                .isPresent();
    }

    // --- components ------------------------------------------------------------

    @Test
    void theSpringStereotypesBecomeKinds() {
        assertThat(component("java:com.acme.task.TaskController").kind()).isEqualTo(NodeKind.CONTROLLER);
        assertThat(component("java:com.acme.task.TaskService").kind()).isEqualTo(NodeKind.SERVICE);
        assertThat(component("java:com.acme.task.TaskOperations").kind()).isEqualTo(NodeKind.INTERFACE);
        assertThat(component("java:com.acme.task.TaskRepository").kind()).isEqualTo(NodeKind.REPOSITORY);
        assertThat(component("java:com.acme.task.Task").kind()).isEqualTo(NodeKind.ENTITY);
        assertThat(component("java:com.acme.task.TaskDto").kind()).isEqualTo(NodeKind.DTO);
        assertThat(component("java:com.acme.task.TaskMapper").kind()).isEqualTo(NodeKind.CLASS);
        assertThat(component("java:com.acme.task.TaskApiTest").kind()).isEqualTo(NodeKind.TEST);
    }

    @Test
    void aClassCarriesItsStructureFromTheAst() {
        CodeStructure s = component("java:com.acme.task.TaskService").structure();
        assertThat(s.packageName()).isEqualTo("com.acme.task");
        assertThat(s.implementsTypes()).containsExactly("TaskOperations");
        assertThat(s.annotations()).containsExactly("Service");
        assertThat(s.fields()).extracting(CodeStructure.CodeMember::name).containsExactly("tasks", "projects");
        assertThat(s.dependencies()).contains("TaskRepository", "ProjectRepository");
        assertThat(s.methods()).extracting(CodeStructure.CodeMethod::name).containsExactly("findAll", "find", "create");
        CodeStructure.CodeMethod create = s.methods().get(2);
        assertThat(create.signature()).isEqualTo("Task create(String title, Long projectId)");
        assertThat(create.visibility()).isEqualTo("public");
        assertThat(create.exceptions()).containsExactly("ArchivedProjectException");
        assertThat(create.calls()).contains("projects.findById()", "tasks.save()");
        assertThat(s.exceptions()).contains("TaskNotFoundException", "ArchivedProjectException");
    }

    @Test
    void pseudocodeComesFromTheMethodBodies() {
        CodeStructure.CodeMethod create = component("java:com.acme.task.TaskService").structure().methods().get(2);
        assertThat(create.pseudocode()).isEqualTo("""
                LET project = projects.findById(projectId).orElseThrow()
                IF project.isArchived()
                  REJECT ArchivedProjectException
                LET task = new Task(title, project)
                CALL tasks.save(task)
                RETURN task""");
        assertThat(component("java:com.acme.task.TaskService").pseudocode()).startsWith("findAll():\n  RETURN tasks.findAll()");
    }

    @Test
    void theJavadocIsTheDescription() {
        assertThat(component("java:com.acme.task.TaskController").description()).isEqualTo("The REST face of tasks.");
        assertThat(component("java:com.acme.task.TaskService").description()).contains("findAll, find, create");
    }

    @Test
    void everyMappingOfAControllerIsAnEndpoint() {
        List<Component> endpoints = result.components().stream().filter(c -> c.kind() == NodeKind.ENDPOINT).toList();
        assertThat(endpoints).extracting(Component::name)
                .containsExactlyInAnyOrder("GET /api/tasks", "POST /api/tasks", "GET /api/tasks/{id}", "POST /v1/completions");
        CodeStructure post = endpoints.stream().filter(e -> e.name().equals("POST /api/tasks")).findFirst().orElseThrow().structure();
        assertThat(post.httpMethod()).isEqualTo("POST");
        assertThat(post.route()).isEqualTo("/api/tasks");
        assertThat(post.controller()).isEqualTo("TaskController");
        assertThat(post.requestDto()).isEqualTo("TaskRequest");
        assertThat(post.responseDto()).isEqualTo("ResponseEntity<TaskDto>");
        assertThat(post.service()).isEqualTo("TaskService, TaskMapper");
        assertThat(post.statusCodes()).containsExactly("201 Created");
        assertThat(post.authorization()).isEqualTo("autenticato (regola globale)");
    }

    @Test
    void aTableIsCreatedByOneMigrationAndAlteredByAnother() {
        Component tasks = component("table:tasks");
        assertThat(tasks.kind()).isEqualTo(NodeKind.DATABASE);
        assertThat(tasks.filePath()).endsWith("V1__create_tasks.sql");
        assertThat(tasks.structure().columns()).containsExactly("id", "title", "project_id", "priority");
    }

    @Test
    void theFrontendIsReadFileByFile() {
        Component app = component("ts:web/src/App.tsx");
        assertThat(app.kind()).isEqualTo(NodeKind.COMPONENT);
        assertThat(app.name()).isEqualTo("App");
        assertThat(app.description()).isEqualTo("The console's root: the list of tasks.");
        assertThat(app.structure().props()).containsExactly("title", "compact");
        assertThat(app.structure().hooks()).containsExactly("useTasks");
        assertThat(component("ts:web/src/hooks/useTasks.ts").kind()).isEqualTo(NodeKind.HOOK);
        assertThat(component("ts:web/src/api/client.ts").kind()).isEqualTo(NodeKind.CLASS);
        assertThat(component("ts:web/src/App.test.tsx").kind()).isEqualTo(NodeKind.TEST);
        assertThat(component("py:engine/app/main.py").kind()).isEqualTo(NodeKind.CONTROLLER);
        assertThat(component("py:engine/app/main.py").description()).isEqualTo("The engine's HTTP face.");
        assertThat(component("py:engine/app/models.py").kind()).isEqualTo(NodeKind.DTO);
    }

    // --- relations: the directive's examples ------------------------------------

    @Test
    void aServiceImplementsItsInterface() {
        assertRelation("java:com.acme.task.TaskService", RelationType.IMPLEMENTS, "java:com.acme.task.TaskOperations");
    }

    @Test
    void aServiceThatCallsARepositoryIsServiceToRepositoryWithTheMethodsItCalls() {
        BlueprintScanner.Relation r = relation("java:com.acme.task.TaskService", RelationType.SERVICE_TO_REPOSITORY,
                "java:com.acme.task.TaskRepository").orElseThrow();
        assertThat(r.label()).isEqualTo("findAll(), findById(), save()");
        assertThat(r.codePath()).endsWith("TaskService.java");
        assertThat(r.line()).isEqualTo(20);
        // the injection is said by the stronger relation: no parallel USES
        assertThat(relation("java:com.acme.task.TaskService", RelationType.USES, "java:com.acme.task.TaskRepository")).isEmpty();
        // across packages, through the explicit import
        assertRelation("java:com.acme.task.TaskService", RelationType.SERVICE_TO_REPOSITORY, "java:com.acme.project.ProjectRepository");
    }

    @Test
    void aControllerCallsTheServiceItIsGivenAndItsEndpointsDelegateToIt() {
        assertRelation("java:com.acme.task.TaskController", RelationType.CALLS, "java:com.acme.task.TaskService");
        String post = "endpoint:com.acme.task.TaskController#create:POST /api/tasks";
        assertRelation(post, RelationType.ENDPOINT_TO_SERVICE, "java:com.acme.task.TaskService");
        assertRelation(post, RelationType.BELONGS_TO, "java:com.acme.task.TaskController");
        assertRelation(post, RelationType.VALIDATES, "java:com.acme.task.dto.TaskRequest");
        assertRelation(post, RelationType.MAPS_TO, "java:com.acme.task.TaskDto");
        // TaskMapper is not a service: the endpoint calls it, plainly
        assertRelation(post, RelationType.CALLS, "java:com.acme.task.TaskMapper");
    }

    @Test
    void theDataPathRunsFromRepositoryToEntityToTable() {
        assertRelation("java:com.acme.task.TaskRepository", RelationType.MAPS_TO, "java:com.acme.task.Task");
        assertRelation("java:com.acme.task.Task", RelationType.ENTITY_TO_TABLE, "table:tasks");
        assertRelation("table:tasks", RelationType.BELONGS_TO, "table:projects");
        assertRelation("java:com.acme.task.TaskService", RelationType.CREATES, "java:com.acme.task.Task");
    }

    @Test
    void theEntityOfAProjectWithoutTableAnnotationIsLookedUpBySnakeCase() {
        // Project has no @Table: its table is "project", which the migrations do not create
        assertThat(component("java:com.acme.project.Project").structure().table()).isEqualTo("project");
    }

    @Test
    void testsCoverWhatTheyCallByRouteAndByName() {
        String test = "java:com.acme.task.TaskApiTest";
        assertRelation(test, RelationType.TEST_COVERS, "endpoint:com.acme.task.TaskController#one:GET /api/tasks/{id}");
        assertRelation(test, RelationType.TEST_COVERS, "endpoint:com.acme.task.TaskController#create:POST /api/tasks");
        assertThat(relation(test, RelationType.TEST_COVERS, "endpoint:com.acme.task.TaskController#list:GET /api/tasks")).isEmpty();
        assertRelation("ts:web/src/App.test.tsx", RelationType.TEST_COVERS, "ts:web/src/App.tsx");
        assertThat(component("endpoint:com.acme.task.TaskController#one:GET /api/tasks/{id}").structure().relatedTests())
                .containsExactly("TaskApiTest");
    }

    @Test
    void theFrontendUsesItsHooksAndComponentsAndCallsTheApi() {
        assertRelation("ts:web/src/App.tsx", RelationType.USES, "ts:web/src/hooks/useTasks.ts");
        assertRelation("ts:web/src/App.tsx", RelationType.USES, "ts:web/src/TaskList.tsx");
        assertRelation("ts:web/src/hooks/useTasks.ts", RelationType.USES, "ts:web/src/api/client.ts");
        assertRelation("ts:web/src/api/client.ts", RelationType.CALLS, "endpoint:com.acme.task.TaskController#one:GET /api/tasks/{id}");
        assertRelation("ts:web/src/api/client.ts", RelationType.CALLS, "endpoint:com.acme.task.TaskController#create:POST /api/tasks");
        assertThat(relation("ts:web/src/api/client.ts", RelationType.CALLS,
                "endpoint:com.acme.task.TaskController#list:GET /api/tasks")).isEmpty();
        assertRelation("py:engine/app/main.py", RelationType.IMPORTS, "py:engine/app/models.py");
    }

    @Test
    void anImportOfTheProjectThatNamesNothingIsUnresolvedAndALibraryIsNot() {
        // com.acme.task.Missing is imported and does not exist; Spring, JPA and java.* are not the project's.
        assertThat(result.unresolved()).isEqualTo(1);
    }

    @Test
    void theFidelityOfEachLanguageIsDeclared() {
        assertThat(result.fidelity()).containsEntry("java", "ast").containsEntry("typescript", "lexical")
                .containsEntry("python", "lexical").containsEntry("sql", "lexical");
    }

    @Test
    void routesMatchSegmentBySegment() {
        assertThat(BlueprintScanner.routeMatches("/api/tasks/{}", "/api/tasks/{id}")).isTrue();
        assertThat(BlueprintScanner.routeMatches("/api/tasks/{}/runs", "/api/tasks/{id}/runs")).isTrue();
        assertThat(BlueprintScanner.routeMatches("/api/tasks", "/api/tasks/{id}")).isFalse();
        assertThat(BlueprintScanner.routeMatches("/api/tasks/x{}", "/api/tasks/search")).isFalse();
    }
}
