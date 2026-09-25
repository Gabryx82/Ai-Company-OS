package com.aicompany.backend.ecosystem;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.exception.SoftwareNotFoundException;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.user.service.SecurityLog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starting the ecosystem with AI Company OS (ADR-028): Ollama, Open WebUI and
 * 3D Omniverse -- or whatever the operator lists. For each service, in order:
 *
 * <ol>
 *   <li>is it already running (its health URL answers)? then it is left alone:
 *       no duplicate starts;</li>
 *   <li>otherwise it is launched the way the Software Hub launches it -- the
 *       catalog's executable, arguments and environment templates, no shell;</li>
 *   <li>then, in the background, its health URL is polled until it answers or
 *       the timeout passes, and the outcome is recorded.</li>
 * </ol>
 *
 * A service that fails is recorded as FAILED with the reason, and the next one
 * starts anyway: a secondary tool never stops AI Company OS.
 */
@Service
public class EcosystemService {

    private static final Logger LOG = LoggerFactory.getLogger(EcosystemService.class);

    /** The ecosystem's defaults on a fresh database: the order is the dependency order. */
    static final List<Default> DEFAULTS = List.of(
            new Default("ollama", true, 1, 60),
            new Default("open-webui", true, 2, 120),
            new Default("omniverse-3d", true, 3, 120));

    record Default(String key, boolean autostart, int position, int timeoutSeconds) {
    }

    public enum Status { NEVER, ALREADY_RUNNING, STARTING, RUNNING, FAILED }

    public record ServiceView(String key, String name, String url, String healthUrl, String command,
                              String availability, boolean autostart, int position, int timeoutSeconds,
                              Status lastStatus, String lastMessage, Instant lastAttemptAt, long version) {
    }

    private final JdbcTemplate jdbc;
    private final SoftwareService software;
    private final SecurityLog log;
    private final long pollMillis;
    private final ExecutorService waiting = Executors.newVirtualThreadPerTaskExecutor();

    private final ProcessProbe processes;

    public EcosystemService(JdbcTemplate jdbc, SoftwareService software, SecurityLog log, ProcessProbe processes,
                            @Value("${aicos.ecosystem.poll-millis:2000}") long pollMillis) {
        this.processes = processes;
        this.jdbc = jdbc;
        this.software = software;
        this.log = log;
        this.pollMillis = pollMillis;
    }

    @PreDestroy
    void stopWaiting() {
        waiting.shutdownNow();
    }

    /** Inserts the default rows that are missing; an operator's choices are never overwritten. */
    @Transactional
    public void ensureDefaults() {
        for (Default d : DEFAULTS) {
            jdbc.update("INSERT INTO ecosystem_autostart (software_key, autostart, position, startup_timeout_seconds) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (software_key) DO NOTHING", d.key(), d.autostart(), d.position(),
                    d.timeoutSeconds());
        }
    }

    @Transactional(readOnly = true)
    public List<ServiceView> status() {
        List<ServiceView> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM ecosystem_autostart ORDER BY position, software_key")) {
            out.add(view(row));
        }
        return out;
    }

    /** Whether it starts with AI Company OS, in which order, and how long to wait for it. */
    @Transactional
    public ServiceView configure(String key, boolean autostart, int position, int timeoutSeconds,
                                 Precondition precondition) {
        if (timeoutSeconds < 5 || timeoutSeconds > 600) {
            throw new RequestValidationException("timeoutSeconds", "must be between 5 and 600");
        }
        Map<String, Object> row = lock(key);
        precondition.requireSatisfiedBy(((Number) row.get("version")).longValue());
        jdbc.update("UPDATE ecosystem_autostart SET autostart = ?, position = ?, startup_timeout_seconds = ?, "
                + "version = version + 1, updated_at = now() WHERE software_key = ?", autostart, position, timeoutSeconds, key);
        return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
    }

    /** Adds a catalog entry to the ecosystem list (not started automatically until configured so). */
    @Transactional
    public ServiceView add(String key) {
        software.findByKey(key); // 404 when the catalog does not know it
        jdbc.update("INSERT INTO ecosystem_autostart (software_key, autostart, position) VALUES (?, FALSE, "
                + "(SELECT COALESCE(MAX(position), 0) + 1 FROM ecosystem_autostart)) ON CONFLICT (software_key) DO NOTHING", key);
        return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
    }

    /** Every service marked autostart, in order. Never throws: each failure is recorded and the next one goes. */
    public List<ServiceView> startAll(String by) {
        List<String> keys = jdbc.queryForList(
                "SELECT software_key FROM ecosystem_autostart WHERE autostart ORDER BY position, software_key", String.class);
        for (String key : keys) {
            try {
                start(key, by);
            } catch (RuntimeException e) {
                LOG.warn("Ecosystem service '{}' could not be started: {}", key, e.getMessage());
                record(key, Status.FAILED, e.getMessage());
            }
        }
        return status();
    }

    /** Starts one service unless it is already running; the outcome arrives in the background. */
    public ServiceView start(String key, String by) {
        lockless(key);
        software.refreshDetection();
        SoftwareService.Detected detected = software.findByKey(key);
        if (detected.detection().availability() == Availability.RUNNING) {
            record(key, Status.ALREADY_RUNNING, "Già attivo su " + detected.software().getUrl() + ": nessun nuovo avvio.");
            return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
        }
        if (processes.isRunning(detected.detection().executable())) {
            record(key, Status.ALREADY_RUNNING, "L'applicazione è già aperta, ma il suo servizio non risponde"
                    + (detected.software().getHealthUrl() == null ? "" : " su " + detected.software().getHealthUrl())
                    + ": avvialo dall'applicazione. Nessun nuovo avvio.");
            return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
        }
        LaunchPlan plan;
        try {
            plan = software.launchIn(key, null, List.of());
        } catch (RuntimeException e) {
            record(key, Status.FAILED, e.getMessage());
            return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
        }
        log.record(SecurityLog.PROCESS_STARTED, by, null, key + ": " + String.join(" ", plan.command()));
        int timeout = jdbc.queryForObject("SELECT startup_timeout_seconds FROM ecosystem_autostart WHERE software_key = ?",
                Integer.class, key);
        record(key, Status.STARTING, "Avviato: attendo che risponda (massimo " + timeout + " s).");
        waiting.submit(() -> awaitHealthy(key, timeout));
        return view(jdbc.queryForMap("SELECT * FROM ecosystem_autostart WHERE software_key = ?", key));
    }

    private void awaitHealthy(String key, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollMillis);
                software.refreshDetection();
                if (software.findByKey(key).detection().availability() == Availability.RUNNING) {
                    record(key, Status.RUNNING, "Attivo e raggiungibile.");
                    LOG.info("Ecosystem service '{}' is up", key);
                    return;
                }
            }
            record(key, Status.FAILED, "Avviato, ma non risponde dopo " + timeoutSeconds + " s: controlla la sua finestra o il suo log.");
            LOG.warn("Ecosystem service '{}' did not answer within {} s", key, timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            record(key, Status.FAILED, e.getMessage());
        }
    }

    // --- rows ------------------------------------------------------------------------------

    private void record(String key, Status status, String message) {
        String text = message == null ? null : (message.length() > 1000 ? message.substring(0, 1000) : message);
        jdbc.update("UPDATE ecosystem_autostart SET last_status = ?, last_message = ?, last_attempt_at = ?, "
                + "updated_at = now() WHERE software_key = ?", status.name(), text, Timestamp.from(Instant.now()), key);
    }

    private Map<String, Object> lock(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ecosystem_autostart WHERE software_key = ? FOR UPDATE", key);
        if (rows.isEmpty()) {
            throw new SoftwareNotFoundException(key);
        }
        return rows.getFirst();
    }

    private void lockless(String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ecosystem_autostart WHERE software_key = ?", Integer.class, key);
        if (n == null || n == 0) {
            throw new SoftwareNotFoundException(key);
        }
    }

    private ServiceView view(Map<String, Object> row) {
        String key = (String) row.get("software_key");
        String name = key;
        String url = null;
        String health = null;
        String command = null;
        String availability = "UNKNOWN";
        try {
            SoftwareService.Detected d = software.findByKey(key);
            Software s = d.software();
            name = s.getName();
            url = s.getUrl();
            health = s.getHealthUrl();
            availability = d.detection().availability().name();
            command = s.getAppId() != null && s.getExecutable() == null ? "shell:AppsFolder\\" + s.getAppId()
                    : s.getExecutable() == null ? null
                    : (s.getExecutable() + (s.getExecutableArgs().isEmpty() ? "" : " " + String.join(" ", s.getExecutableArgs())));
        } catch (SoftwareNotFoundException missing) {
            availability = "NOT_IN_CATALOG";
        }
        Timestamp at = (Timestamp) row.get("last_attempt_at");
        return new ServiceView(key, name, url, health, command, availability, (Boolean) row.get("autostart"),
                ((Number) row.get("position")).intValue(), ((Number) row.get("startup_timeout_seconds")).intValue(),
                Status.valueOf((String) row.get("last_status")), (String) row.get("last_message"),
                at == null ? null : at.toInstant(), ((Number) row.get("version")).longValue());
    }
}
