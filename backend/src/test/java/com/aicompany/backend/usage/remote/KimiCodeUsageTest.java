package com.aicompany.backend.usage.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHASE 33 (ADR-038): Kimi Code's quota, asked the way the official CLI asks it. */
class KimiCodeUsageTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final String KEY = "test-kimi-key";

    private HttpServer server;
    private final List<String> authorizations = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = """
            {"usage":{"limit":"100","used":"40","remaining":"60","resetTime":"2026-10-15T08:00:00Z"},
             "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                        "detail":{"limit":"20","remaining":"15","resetTime":"2026-10-11T14:00:00Z"}}],
             "user":{"membership":{"level":"LEVEL_INTERMEDIATE"}}}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/coding/v1/usages", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KimiCodeUsage usage(String key) {
        return new KimiCodeUsage("http://127.0.0.1:" + server.getAddress().getPort() + "/coding/v1/", key,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theWeeklyQuotaAndTheFiveHourWindowComeWithTheirResets() {
        KimiCodeUsage.Report report = usage(KEY).report().orElseThrow();

        assertThat(report.problem()).isNull();
        assertThat(report.weekly()).contains(new KimiCodeUsage.Quota(null, 40.0, Instant.parse("2026-10-15T08:00:00Z")));
        // no "used" in the window: limit minus remaining
        assertThat(report.window(300)).contains(new KimiCodeUsage.Quota(300, 25.0, Instant.parse("2026-10-11T14:00:00Z")));
        assertThat(authorizations).containsExactly("Bearer " + KEY);
    }

    @Test
    void kimiIsAskedAtMostOnceAMinute() {
        KimiCodeUsage usage = usage(KEY);
        usage.report();
        usage.report();

        assertThat(authorizations).hasSize(1);
    }

    @Test
    void aRefusedKeyIsSaidWithoutTheKey() {
        status = 401;
        body = "{\"error\":\"invalid\"}";

        KimiCodeUsage.Report report = usage(KEY).report().orElseThrow();

        assertThat(report.quotas()).isEmpty();
        assertThat(report.problem()).contains("401").doesNotContain(KEY);
    }

    @Test
    void anAnswerWithoutQuotasIsAProblemNotAZero() {
        body = "{\"usage\":{}}";

        assertThat(usage(KEY).report().orElseThrow().problem()).contains("senza quote");
    }

    @Test
    void withoutAKeyNothingIsAsked() {
        assertThat(usage("  ").report()).isEmpty();
        assertThat(authorizations).isEmpty();
    }

    @Test
    void theKeyTravelsOnlyOverHttpsOrToThisMachine() {
        assertThatThrownBy(() -> new KimiCodeUsage("http://api.kimi.com/coding/v1", KEY, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KimiCodeUsage("https://api.kimi.com/coding/v1", KEY, Clock.systemUTC()).configured()).isTrue();
    }
}
