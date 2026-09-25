package com.aicompany.backend.software;

import com.aicompany.backend.catalog.CatalogBootstrap;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Software Hub over HTTP (ADR-019), against a {@link FakeHost}. */
class SoftwareHubApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeHost host;

    @Autowired
    private SoftwareRepository repository;

    @Autowired
    private CatalogBootstrap bootstrap;

    private static final String CUSTOM = """
            {"key":"my-tool","name":"My Tool","category":"EDITOR","role":"Editor","launchKind":"DESKTOP",
             "executable":"%ProgramFiles%\\\\MyTool\\\\tool.exe","capabilities":["Text-Editing"," logs ","logs"]}""";

    /** PHASE 21: what the launcher may execute is an admin's decision, so catalog writes sign in as one. */
    private String admin;

    @BeforeEach
    void removeCustomEntries() throws Exception {
        repository.findByKey("my-tool").ifPresent(repository::delete);
        admin = com.aicompany.backend.support.AdminSession.bearer(mockMvc);
    }

    @Test
    void anOperatorCannotChangeWhatTheLauncherExecutes() throws Exception {
        mockMvc.perform(post("/api/software").contentType(MediaType.APPLICATION_JSON).content(CUSTOM))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/software/postman").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCatalogHoldsEverySoftwareTheDirectiveNamed() throws Exception {
        List<String> required = List.of("antigravity-ide", "antigravity", "netbeans", "devin", "intellij-junie",
                "kimi", "notepad-plus-plus", "pycharm", "visual-studio-2022", "vscode-continue", "warp", "verdent",
                "webstorm", "mysql-workbench", "virtualbox", "postman", "drawio", "chatgpt-classic", "codex",
                "claude-code", "opencode", "open-webui", "omniverse-3d", "github", "gitlab", "supabase", "vercel",
                "gmail", "google-drive", "clickup", "dwarfstar4", "gemini", "powershell");

        mockMvc.perform(get("/api/software"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItems(required.toArray(String[]::new))));
    }

    @Test
    void anEntryCarriesItsDetectionWhichIsNeverStored() throws Exception {
        host.script("postman", Detection.of(Availability.NOT_INSTALLED, "gone"));

        mockMvc.perform(get("/api/software/postman"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.availability").value("NOT_INSTALLED"))
                .andExpect(jsonPath("$.launchable").value(false));

        host.script("postman", Detection.of(Availability.INSTALLED, "back"));

        mockMvc.perform(get("/api/software/postman"))
                .andExpect(jsonPath("$.availability").value("INSTALLED"))
                .andExpect(jsonPath("$.launchable").value(true));
    }

    @Test
    void theOrchestratorsExecutionTargetsAreMarked() throws Exception {
        mockMvc.perform(get("/api/software"))
                .andExpect(jsonPath("$[?(@.executionTarget == true)].key",
                        hasItems("claude-code", "codex", "antigravity-ide", "opencode")));
    }

    @Test
    void incorporabilityIsTheMeasuredOneOpenWebUiYesGmailNo() throws Exception {
        mockMvc.perform(get("/api/software/open-webui"))
                .andExpect(jsonPath("$.embeddable").value(true))
                .andExpect(jsonPath("$.url").value("http://localhost:8080"));
        mockMvc.perform(get("/api/software/gmail"))
                .andExpect(jsonPath("$.embeddable").value(false))
                .andExpect(jsonPath("$.availability").value("WEB"));
        mockMvc.perform(get("/api/software/dwarfstar4"))
                .andExpect(jsonPath("$.availability").value("INCOMPATIBLE_HARDWARE"));
    }

    @Test
    void aLaunchStartsExactlyTheCatalogsProgram() throws Exception {
        mockMvc.perform(post("/api/software/postman/launch"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command[0]").value(FakeHost.FAKE_EXECUTABLE.toString()))
                .andExpect(jsonPath("$.folderOpened").value(false));

        assertThat(host.launches()).extracting(LaunchPlan::command)
                .containsExactly(List.of(FakeHost.FAKE_EXECUTABLE.toString()));
    }

    @Test
    void aWebEntryIsNeverLaunchedByTheControlPlane() throws Exception {
        mockMvc.perform(post("/api/software/github/launch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:software-not-launchable"));

        assertThat(host.launches()).isEmpty();
    }

    @Test
    void aLaunchBodyCannotNameAProgramOnlyAProject() throws Exception {
        mockMvc.perform(post("/api/software/postman/launch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 999999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:software-not-launchable"));

        assertThat(host.launches()).isEmpty();
    }

    @Test
    void anUnknownKeyIsA404() throws Exception {
        mockMvc.perform(post("/api/software/nope/launch"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:software-not-found"));
    }

    @Test
    void theOperatorAddsAndEditsEntriesUnderThePreconditionProtocol() throws Exception {
        mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(CUSTOM))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/software/my-tool"))
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.contains("text-editing", "logs")));

        mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(CUSTOM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:software-key-conflict"));

        String edited = CUSTOM.replace("\"My Tool\"", "\"My Tool 2\"");
        mockMvc.perform(put("/api/software/my-tool").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(edited))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/software/my-tool").header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(edited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My Tool 2"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        mockMvc.perform(put("/api/software/my-tool").header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(edited))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void aKeyIsAnIdentityAndCannotBeEdited() throws Exception {
        mockMvc.perform(put("/api/software/postman").header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.key").exists());
    }

    @Test
    void anEntryThatCouldNotBeOpenedIsRefusedAsAValidationFailure() throws Exception {
        mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content("""
                        {"key":"broken","name":"Broken","category":"IDE","role":"x","launchKind":"DESKTOP"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.executable").exists());
    }

    @Test
    void theBootstrapNeverOverwritesWhatTheOperatorEdited() throws Exception {
        String etag = mockMvc.perform(get("/api/software/notepad-plus-plus")).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(put("/api/software/notepad-plus-plus").header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Notepad++ (mio)","category":"EDITOR","role":"Editor personale",
                                 "launchKind":"DESKTOP","executable":"%ProgramFiles%\\\\Notepad++\\\\notepad++.exe"}"""))
                .andExpect(status().isOk());

        bootstrap.run(new DefaultApplicationArguments());

        mockMvc.perform(get("/api/software/notepad-plus-plus"))
                .andExpect(jsonPath("$.name").value("Notepad++ (mio)"))
                .andExpect(jsonPath("$.role").value("Editor personale"));
    }

    @Test
    void refreshForgetsTheCachedDetection() throws Exception {
        mockMvc.perform(post("/api/software/refresh")).andExpect(status().isNoContent());

        assertThat(host.refreshes()).isEqualTo(1);
    }
}
