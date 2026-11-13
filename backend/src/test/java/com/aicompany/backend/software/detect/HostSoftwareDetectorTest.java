package com.aicompany.backend.software.detect;

import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.model.SoftwareCategory;
import com.aicompany.backend.software.model.SoftwareDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-046: detection of the catalog's local services. The Software Hub, the agents' bindings and the dashboard
 * all wait for it; a service is asked over HTTP, and each one asked after the other made the catalog as slow as
 * the sum of its answers. (A stopped one costs nothing: Java's client hears the refusal in milliseconds, measured
 * 2026-11-12, where a blocking connect on Windows waits two seconds for it.)
 */
class HostSoftwareDetectorTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void servicesAreAskedTogetherSoTheCatalogTakesTheTimeOfTheSlowestOne() throws IOException {
        List<Software> services = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            services.add(service("slow-" + i, "http://127.0.0.1:" + slowServer(Duration.ofMillis(700)) + "/health"));
        }
        HostSoftwareDetector detector = new HostSoftwareDetector(Map.of(), false, Clock.systemUTC());

        long start = System.nanoTime();
        detector.prepare(services);
        List<Availability> found = services.stream().map(detector::detect).map(Detection::availability).toList();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(found).containsOnly(Availability.RUNNING);
        // One after the other: 2.8 s.
        assertThat(took).isLessThan(Duration.ofMillis(2000));
    }

    @Test
    void whatWasAskedIsKeptAndNotAskedAgainAtTheNextLook() throws IOException {
        int[] asked = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            asked[0]++;
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);
        Software one = service("once", "http://127.0.0.1:" + server.getAddress().getPort() + "/health");
        HostSoftwareDetector detector = new HostSoftwareDetector(Map.of(), false, Clock.systemUTC());

        detector.prepare(List.of(one));
        detector.detect(one);
        detector.prepare(List.of(one));
        detector.detect(one);

        assertThat(asked[0]).isEqualTo(1);
    }

    /**
     * Where a command is was looked for on disk at every catalog read: every folder of the PATH with every extension,
     * for each command-line tool. Found once, it is kept like a probe; «Aggiorna» looks again.
     */
    @Test
    void whereACommandIsIsKeptUntilTheCatalogIsRefreshed() throws IOException {
        java.nio.file.Path bin = java.nio.file.Files.createTempDirectory("aicos-path");
        java.nio.file.Path tool = java.nio.file.Files.writeString(bin.resolve("tool.cmd"), "@echo off");
        Software cli = new Software("tool", new SoftwareDefinition("Tool", SoftwareCategory.AI_LOCAL, "test", "test", List.of(),
                List.of(), LaunchKind.CLI, null, null, List.of(), false, "tool", null, null, false, null, false,
                null, null, null, null, true, List.of(), List.of()));
        HostSoftwareDetector detector = new HostSoftwareDetector(Map.of("PATH", bin.toString(), "PATHEXT", ".CMD"), true,
                Clock.systemUTC());

        assertThat(detector.detect(cli).availability()).isEqualTo(Availability.INSTALLED);
        java.nio.file.Files.delete(tool);
        assertThat(detector.detect(cli).availability()).isEqualTo(Availability.INSTALLED);

        detector.refresh();
        assertThat(detector.detect(cli).availability()).isEqualTo(Availability.NOT_INSTALLED);
    }

    /**
     * The ecosystem's start (ADR-028) waits for a service by forgetting what the services answered, every couple of
     * seconds. It forgot everything: the next page read the Start menu again with PowerShell (~2 s) while it waited.
     */
    @Test
    void forgettingTheServicesAsksThemAgainAndKeepsEverythingElse() throws IOException {
        int[] asked = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            asked[0]++;
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);
        Software service = service("svc", "http://127.0.0.1:" + server.getAddress().getPort() + "/health");
        java.nio.file.Path bin = java.nio.file.Files.createTempDirectory("aicos-path");
        java.nio.file.Path tool = java.nio.file.Files.writeString(bin.resolve("tool.cmd"), "@echo off");
        Software cli = new Software("tool", new SoftwareDefinition("Tool", SoftwareCategory.AI_LOCAL, "test", "test", List.of(),
                List.of(), LaunchKind.CLI, null, null, List.of(), false, "tool", null, null, false, null, false,
                null, null, null, null, true, List.of(), List.of()));
        HostSoftwareDetector detector = new HostSoftwareDetector(Map.of("PATH", bin.toString(), "PATHEXT", ".CMD"), true,
                Clock.systemUTC());
        detector.prepare(List.of(service, cli));
        assertThat(detector.detect(cli).availability()).isEqualTo(Availability.INSTALLED);
        java.nio.file.Files.delete(tool);

        detector.forgetServices();
        detector.prepare(List.of(service, cli));

        assertThat(asked[0]).isEqualTo(2);
        assertThat(detector.detect(cli).availability()).isEqualTo(Availability.INSTALLED);
    }

    // --- helpers -------------------------------------------------------------------------

    private int slowServer(Duration delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", exchange -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return server.getAddress().getPort();
    }

    private static Software service(String key, String healthUrl) {
        return new Software(key, new SoftwareDefinition(key, SoftwareCategory.AI_LOCAL, "test", "test", List.of(), List.of(),
                LaunchKind.LOCAL_SERVICE, null, null, List.of(), false, null, healthUrl, healthUrl, false, null, false,
                null, null, null, null, true, List.of(), List.of()));
    }
}
