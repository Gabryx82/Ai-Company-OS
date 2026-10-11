package com.aicompany.backend.usage;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.usage.local.AntigravityStateFixture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 33 (ADR-038): the new sources over HTTP, against an Antigravity state and a Kimi
 * Code that this test owns -- Antigravity's quotas per model, Kimi Code's own figures.
 * Its own application context: the sources are configured at startup.
 */
class UsageSourcesApiTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant SOON = NOW.plus(Duration.ofHours(2));
    private static final Instant LATER = NOW.plus(Duration.ofHours(3));
    private static final Instant WEEK_END = NOW.plus(Duration.ofDays(3));
    private static HttpServer kimi;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws Exception {
        Path manager = Files.createTempDirectory("aicos-antigravity");
        AntigravityStateFixture.writeDatabase(manager, new AntigravityStateFixture()
                .model("Gemini 3.1 Pro (High)", 0.4f, LATER)
                .model("Gemini 3.8 Flash (High)", 1.0f, SOON)
                .model("Claude Opus 4.6 (Thinking)", 0.5f, NOW.minus(Duration.ofHours(1)))
                .storedValue());
        Path ide = Files.createTempDirectory("aicos-antigravity-ide");
        AntigravityStateFixture.writeDatabase(ide, new AntigravityStateFixture()
                .model("Gemini 3.1 Pro (High)", 0.1f, NOW.minus(Duration.ofDays(2)))
                .storedValue());

        kimi = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        kimi.createContext("/coding/v1/usages", exchange -> {
            boolean authorised = "Bearer kimi-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (authorised ? """
                    {"usage":{"limit":"100","used":"40","resetTime":"%s"},
                     "limits":[{"window":{"duration":5,"timeUnit":"TIME_UNIT_HOUR"},
                                "detail":{"limit":"10","used":"9","resetTime":"%s"}}]}""".formatted(WEEK_END, SOON)
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authorised ? 200 : 401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        kimi.start();

        registry.add("aicos.usage.antigravity-home", manager::toString);
        registry.add("aicos.usage.antigravity-ide-home", ide::toString);
        registry.add("aicos.usage.kimi-code-base-url", () -> "http://127.0.0.1:" + kimi.getAddress().getPort() + "/coding/v1");
        registry.add("aicos.usage.kimi-code-api-key", () -> "kimi-test-key");
    }

    @AfterAll
    static void stopKimi() {
        kimi.stop(0);
    }

    @Test
    void antigravityGivesEveryModelItsQuotaAndTheMostConsumedOneOnTop() throws Exception {
        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].status").value("MEASURED"))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].windowKind").value("PER_MODEL"))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].usedPercent").value(60.0))
                // the next reset of a model that has been used, not of an untouched one
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].nextResetAt").value(LATER.toString()))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].modelQuotas[*].model", contains(
                        "Gemini 3.1 Pro (High)", "Gemini 3.8 Flash (High)", "Claude Opus 4.6 (Thinking)")))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].modelQuotas[2].resetSinceObservation").value(true))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].modelQuotas[2].usedPercent").value(0.0))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].modelQuotas[2].resetsAt", hasItems(nullValue())))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-models')].detail",
                        hasItems(containsString("Stato salvato dall'app"))));
    }

    @Test
    void anAntigravityStateWhoseResetsHaveAllPassedSaysSo() throws Exception {
        mockMvc.perform(get("/api/usage"))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-ide-models')].status").value("RESET_SINCE_OBSERVATION"))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-ide-models')].usedPercent").value(0.0))
                .andExpect(jsonPath("$[?(@.key == 'antigravity-ide-models')].nextResetAt", hasItems(nullValue())));
    }

    @Test
    void kimiCodeGivesItsWeeklyQuotaAndItsFiveHourWindow() throws Exception {
        mockMvc.perform(get("/api/usage"))
                .andExpect(jsonPath("$[?(@.key == 'kimi-code-weekly')].status").value("MEASURED"))
                .andExpect(jsonPath("$[?(@.key == 'kimi-code-weekly')].usedPercent").value(40.0))
                .andExpect(jsonPath("$[?(@.key == 'kimi-code-weekly')].nextResetAt").value(WEEK_END.toString()))
                .andExpect(jsonPath("$[?(@.key == 'kimi-code-5h')].usedPercent").value(90.0))
                .andExpect(jsonPath("$[?(@.key == 'kimi-code-5h')].windowStart", hasItems(notNullValue())));
    }
}
