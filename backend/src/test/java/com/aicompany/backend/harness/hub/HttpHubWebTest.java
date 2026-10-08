package com.aicompany.backend.harness.hub;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-79): the operator's GitHub token raises the API's limit from 60
 * requests an hour to 5000. It goes to the GitHub API and to no other host.
 */
class HttpHubWebTest {

    private static final String TOKEN = "github_pat_test_only_0123456789";

    @Test
    void theTokenGoesToTheGitHubApiAndNowhereElse() {
        HttpRequest api = HttpHubWeb.request(URI.create("https://api.github.com/repos/anthropics/skills/git/trees/HEAD"), TOKEN);
        assertThat(api.headers().firstValue("Authorization")).contains("Bearer " + TOKEN);
        assertThat(api.headers().firstValue("Accept")).contains("application/vnd.github+json");

        for (String other : new String[] {"https://raw.githubusercontent.com/anthropics/skills/HEAD/skills/pdf/SKILL.md",
                "https://skills.sh/api/search?q=pdf", "https://registry.modelcontextprotocol.io/v0/servers"}) {
            assertThat(HttpHubWeb.request(URI.create(other), TOKEN).headers().firstValue("Authorization")).as(other).isEmpty();
        }
    }

    @Test
    void withoutATokenTheApiIsAskedAnonymously() {
        HttpRequest api = HttpHubWeb.request(URI.create("https://api.github.com/repos/a/b"), "  ");
        assertThat(api.headers().firstValue("Authorization")).isEmpty();
        assertThat(new HttpHubWeb("").githubAuthenticated()).isFalse();
        assertThat(new HttpHubWeb(TOKEN).githubAuthenticated()).isTrue();
    }
}
