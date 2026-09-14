package com.aicompany.backend.project;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the project registry: the CRUD surface, the validation and
 * conflict rules, and the absence of a physical delete.
 */
class ProjectApiTest extends AbstractPostgresTest {

    /**
     * Turkish dotless i, U+0131. PostgreSQL folds it onto "I" with upper() but
     * not with lower(), which is how the review found that the service and the
     * unique index were applying two different rules.
     */
    private static final String DOTLESS_I = "\u0131";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository repository;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void clearProjects() {
        // Tasks go first. Since V3 they hold a foreign key to projects, and the
        // database refuses to delete a project a task still points at -- which is
        // exactly the behaviour TaskProjectRelationPersistenceTest asserts.
        taskRepository.deleteAll();
        repository.deleteAll();
    }

    // --- create ------------------------------------------------------------

    @Test
    void validProjectIsCreatedActiveWithALocationHeader() throws Exception {

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Company OS","description":"the control plane"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Company OS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void locationHeaderPointsAtSomethingThatCanBeRead() throws Exception {

        String location = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Company OS"}"""))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Company OS"));
    }

    @Test
    void emptyBodyIsRejectedAndNothingIsPersisted() throws Exception {

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        assertThat(repository.count()).isZero();
    }

    @Test
    void blankNameIsRejected() throws Exception {

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}"""))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void overlongNameIsRejected() throws Exception {

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}""".formatted("x".repeat(121))))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void statusInRequestBodyIsIgnored() throws Exception {

        // The create DTO has neither an id nor a status component, so a client
        // can neither choose the identifier nor create an already archived
        // project.
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":424242,"name":"Company OS","status":"ARCHIVED"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(424242)));
    }

    @Test
    void duplicateNameIsRejectedIgnoringCase() throws Exception {

        createProject("Company OS");

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"company os"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Project name already in use"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void namesThatOnlyCollideUnderUpperCaseAreNotDuplicates() throws Exception {

        // Regression for the review finding F-1. The service used to ask
        // upper(name) = upper(?) while the unique index is on lower(name), so
        // creating "i-dotless" next to "I" was rejected with 409 even though the
        // database -- the actual invariant, ADR-004 section 5 -- allows both.
        createProject("I");

        assertThat(repository.existsByNormalisedName(DOTLESS_I)).isFalse();

        postProject(DOTLESS_I).andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void theApiAndTheDatabaseAgreeOnWhatADuplicateIs() throws Exception {

        // Whatever the service accepts, the index must accept, and the other way
        // round. Checked on the pair that used to disagree and on the ASCII pair
        // that always agreed.
        createProject("I");

        assertThat(repository.existsByNormalisedName(DOTLESS_I)).isFalse();
        assertThat(repository.existsByNormalisedName("i")).isTrue();

        postProject(DOTLESS_I).andExpect(status().isCreated());
        postProject("i").andExpect(status().isConflict());

        assertThat(repository.count()).isEqualTo(2);
    }

    // --- read --------------------------------------------------------------

    @Test
    void unknownProjectIsReportedAsNotFound() throws Exception {

        mockMvc.perform(get("/api/projects/404404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Project not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listingCanBeFilteredByStatus() throws Exception {

        Long active = createProject("Active one");
        Long archived = createProject("Archived one");
        mockMvc.perform(post("/api/projects/" + archived + "/archive")).andExpect(status().isOk());

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/projects").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.intValue()));

        mockMvc.perform(get("/api/projects").param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(archived.intValue()));
    }

    @Test
    void unknownStatusFilterIsARequestError() throws Exception {

        mockMvc.perform(get("/api/projects").param("status", "DELETED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameter"));
    }

    // --- update ------------------------------------------------------------

    @Test
    void updateReplacesTheDescriptiveFields() throws Exception {

        Long id = createProject("Company OS");

        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Company OS renamed","description":"now with a description"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Company OS renamed"))
                .andExpect(jsonPath("$.description").value("now with a description"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void updateKeepingTheSameNameIsNotAConflictWithItself() throws Exception {

        Long id = createProject("Company OS");

        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Company OS","description":"only the description changed"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void updateToANameOwnedByAnotherProjectIsAConflict() throws Exception {

        createProject("Company OS");
        Long second = createProject("Second project");

        mockMvc.perform(put("/api/projects/" + second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"COMPANY OS"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void updatingAnArchivedProjectIsAConflict() throws Exception {

        Long id = createProject("Company OS");
        mockMvc.perform(post("/api/projects/" + id + "/archive")).andExpect(status().isOk());

        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed while archived"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Archived project is immutable"));

        // Refused, and nothing changed: not a partial write.
        Project untouched = repository.findById(id).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("Company OS");
        assertThat(untouched.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void restoringMakesTheSameUpdateSucceed() throws Exception {

        Long id = createProject("Company OS");
        mockMvc.perform(post("/api/projects/" + id + "/archive")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + id + "/restore")).andExpect(status().isOk());

        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed after restore"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed after restore"));
    }

    @Test
    void updateOfAnUnknownProjectIsNotFound() throws Exception {

        mockMvc.perform(put("/api/projects/404404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ghost"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateWithAnInvalidBodyIsRejected() throws Exception {

        Long id = createProject("Company OS");

        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(repository.findById(id).orElseThrow().getName()).isEqualTo("Company OS");
    }

    // --- lifecycle ---------------------------------------------------------

    @Test
    void archiveMovesTheProjectOutOfTheRegistryWithoutDeletingIt() throws Exception {

        Long id = createProject("Company OS");

        mockMvc.perform(post("/api/projects/" + id + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findById(id).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void archivingTwiceIsAConflict() throws Exception {

        Long id = createProject("Company OS");
        mockMvc.perform(post("/api/projects/" + id + "/archive")).andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/" + id + "/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal project state transition"));

        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    void restoreBringsAnArchivedProjectBack() throws Exception {

        Long id = createProject("Company OS");
        mockMvc.perform(post("/api/projects/" + id + "/archive")).andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/" + id + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void restoringAnActiveProjectIsAConflict() throws Exception {

        Long id = createProject("Company OS");

        mockMvc.perform(post("/api/projects/" + id + "/restore"))
                .andExpect(status().isConflict());
    }

    @Test
    void archivingAnUnknownProjectIsNotFound() throws Exception {

        mockMvc.perform(post("/api/projects/404404/archive"))
                .andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoPhysicalDeleteOverHttp() throws Exception {

        Long id = createProject("Company OS");

        // The path exists for other methods, so this is 405 and not 404: the
        // answer is "not this way", and the way is /archive.
        mockMvc.perform(delete("/api/projects/" + id))
                .andExpect(status().isMethodNotAllowed());

        assertThat(repository.findById(id)).isPresent();
    }

    // --- other modules are untouched ---------------------------------------

    // theProjectErrorContractDoesNotLeakIntoTheTaskApi lived here until TASK-005.
    // It asserted the opposite of what is now true -- the contract does reach the
    // task API, deliberately (ADR-007 §6) -- and its assertion was the weak one
    // recorded as TD-21: checking only that $.errors was absent, it would have
    // stayed green if the task API had moved to ProblemDetail without that
    // property. What replaces it is ApiErrorContractTest, which asserts the shape
    // everywhere instead of asserting its absence in one place.

    private Long createProject(String name) {
        return repository.saveAndFlush(new Project(name, null)).getId();
    }

    /**
     * Posts a name as UTF-8 bytes with the encoding stated explicitly, so the
     * non-ASCII cases above test case folding and not the test's own encoding.
     */
    private ResultActions postProject(String name) throws Exception {
        return mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(("{\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8)));
    }
}
