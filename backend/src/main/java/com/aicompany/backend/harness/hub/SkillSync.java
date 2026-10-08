package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.library.SkillLibrary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Hub in step with the applications (ADR-036 §9): every
 * synchronisation re-reads every application's skills (and, when the operator
 * asks for it, the library folder), compares them with the snapshot of the previous
 * one, and records what was installed, removed or changed meanwhile -- by the
 * operator, by an application updating its plugins, or by the Hub itself.
 *
 * <p>It runs on demand and every {@code aicos.hub.sync-interval} (five minutes by
 * default; zero turns the schedule off, as the tests do). The first run only
 * takes the snapshot: a first look has nothing to compare with.
 */
@Service
public class SkillSync {

    private static final Logger LOG = LoggerFactory.getLogger(SkillSync.class);
    static final int KEPT_EVENTS = 1000;
    /**
     * The first rows of the snapshot that are no item: one per location, saying
     * that location has been looked at once ({@code baseline:<location>}).
     * Without them a machine with no skill at all would look like a first run
     * forever, and its first installs would never be reported. And a location
     * read for the first time -- a new application, a folder the Hub learned to
     * read (Claude Desktop from the Microsoft Store, plugins, connectors) -- is
     * photographed, not reported as hundreds of installs that happened today.
     * {@code baseline} alone is the marker of the first version of the snapshot.
     */
    static final String BASELINE = "baseline";
    static final String BASELINE_PREFIX = "baseline:";

    public enum Change { ADDED, REMOVED, MODIFIED }

    public record Event(long id, Instant at, Change change, String app, String location, String name, String path,
                        @Nullable String detail) {
    }

    public record Report(Instant at, boolean firstRun, int total, int added, int removed, int modified,
                         List<Event> events, List<String> libraryCreated, List<String> libraryUpdated,
                         List<String> libraryInvalid) {
    }

    private record Snapshot(String id, String app, String location, String path, String name, String fingerprint,
                            String pluginVersion) {
    }

    private final SkillScanner scanner;
    private final SkillLocations locations;
    private final SkillLibrary library;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration interval;
    private volatile Instant lastRun;
    private ScheduledExecutorService schedule;

    public SkillSync(SkillScanner scanner, SkillLocations locations, SkillLibrary library, JdbcTemplate jdbc,
                     TransactionTemplate transactions, @Value("${aicos.hub.sync-interval:PT5M}") Duration interval) {
        this.scanner = scanner;
        this.locations = locations;
        this.library = library;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.interval = interval;
    }

    public Instant lastRun() {
        return lastRun;
    }

    public Duration interval() {
        return interval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSchedule() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        schedule = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-hub-sync");
            t.setDaemon(true);
            return t;
        });
        schedule.scheduleWithFixedDelay(() -> {
            try {
                sync(false);
            } catch (RuntimeException e) {
                LOG.warn("Scheduled skill synchronisation failed", e);
            }
        }, 20, interval.toSeconds(), TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void stopSchedule() {
        if (schedule != null) {
            schedule.shutdownNow();
        }
    }

    /**
     * One synchronisation now. One at a time. {@code withLibrary} also re-reads the
     * library folder into its index, as «Rileggi cartella» does -- the operator's
     * gesture, not the schedule's: re-indexing touches every entry's version, and a
     * background run must not make the console's open editors stale.
     */
    public synchronized Report sync(boolean withLibrary) {
        SkillLibrary.SyncReport libraryReport = withLibrary ? transactions.execute(status -> library.sync())
                : new SkillLibrary.SyncReport(library.root(), List.of(), List.of(), List.of());
        SkillScanner.Scan scan = scanner.scan();
        Report report = transactions.execute(status -> compare(scan, Objects.requireNonNull(libraryReport)));
        lastRun = report.at();
        return report;
    }

    public List<Event> events(int limit) {
        return jdbc.query("SELECT id, occurred_at, change, app, location, name, relative_path, detail FROM skill_sync_events "
                        + "ORDER BY occurred_at DESC, id DESC LIMIT ?",
                (rs, n) -> new Event(rs.getLong(1), rs.getTimestamp(2).toInstant(), Change.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                Math.max(1, Math.min(limit, KEPT_EVENTS)));
    }

    private Report compare(SkillScanner.Scan scan, SkillLibrary.SyncReport libraryReport) {
        Map<String, Snapshot> before = new HashMap<>();
        jdbc.query("SELECT id, app, location, relative_path, name, fingerprint, plugin_version FROM skill_snapshot",
                rs -> {
                    before.put(rs.getString(1), new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                });
        boolean firstRun = before.isEmpty();
        before.remove(BASELINE);
        Set<String> known = new HashSet<>();
        before.keySet().removeIf(id -> {
            if (id.startsWith(BASELINE_PREFIX)) {
                known.add(id.substring(BASELINE_PREFIX.length()));
                return true;
            }
            return false;
        });
        Instant now = scan.at();
        Timestamp at = Timestamp.from(now);
        for (SkillLocations.Location location : locations.locations()) {
            if (!known.contains(location.id())) {
                jdbc.update("INSERT INTO skill_snapshot (id, app, location, relative_path, name, fingerprint, plugin_version, "
                        + "first_seen_at, last_changed_at) VALUES (?, 'aicos', ?, '', 'baseline', '', NULL, ?, ?)",
                        BASELINE_PREFIX + location.id(), cut(location.id(), 48), at, at);
            }
        }
        List<Event> events = new ArrayList<>();
        Map<String, FoundSkill> current = new HashMap<>();
        for (FoundSkill skill : scan.skills()) {
            current.putIfAbsent(skill.id(), skill);
        }
        for (FoundSkill skill : current.values()) {
            Snapshot was = before.get(skill.id());
            String name = cut(skill.name(), 200);
            String path = cut(skill.relativePath(), 1000);
            if (was == null) {
                jdbc.update("INSERT INTO skill_snapshot (id, app, location, relative_path, name, fingerprint, plugin_version, "
                                + "first_seen_at, last_changed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        skill.id(), skill.app(), skill.location(), path, name, skill.fingerprint(),
                        cut(skill.pluginVersion(), 120), at, at);
                if (known.contains(skill.location())) {
                    events.add(event(now, Change.ADDED, skill, skill.plugin() == null || FoundSkill.PLUGIN.equals(skill.kind())
                            ? (skill.pluginVersion() == null ? null : "Versione " + skill.pluginVersion())
                            : "Plugin " + skill.plugin() + (skill.pluginVersion() == null ? "" : " " + skill.pluginVersion())));
                }
            } else if (!was.fingerprint().equals(skill.fingerprint()) || !Objects.equals(was.pluginVersion(), skill.pluginVersion())) {
                jdbc.update("UPDATE skill_snapshot SET name = ?, fingerprint = ?, plugin_version = ?, last_changed_at = ? WHERE id = ?",
                        name, skill.fingerprint(), cut(skill.pluginVersion(), 120), at, skill.id());
                String detail = !Objects.equals(was.pluginVersion(), skill.pluginVersion()) && skill.plugin() != null
                        ? "Plugin " + skill.plugin() + ": " + was.pluginVersion() + " → " + skill.pluginVersion()
                        : "Contenuto modificato";
                events.add(event(now, Change.MODIFIED, skill, detail));
            }
        }
        for (Snapshot gone : before.values()) {
            if (!current.containsKey(gone.id())) {
                jdbc.update("DELETE FROM skill_snapshot WHERE id = ?", gone.id());
                events.add(new Event(0, now, Change.REMOVED, gone.app(), gone.location(), gone.name(), gone.path(), null));
            }
        }
        for (Event e : events) {
            jdbc.update("INSERT INTO skill_sync_events (occurred_at, change, app, location, name, relative_path, detail) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", at, e.change().name(), e.app(), e.location(), cut(e.name(), 200),
                    cut(e.path(), 1000), cut(e.detail(), 500));
        }
        jdbc.update("DELETE FROM skill_sync_events WHERE id NOT IN (SELECT id FROM skill_sync_events "
                + "ORDER BY occurred_at DESC, id DESC LIMIT " + KEPT_EVENTS + ")");
        int added = (int) events.stream().filter(e -> e.change() == Change.ADDED).count();
        int removed = (int) events.stream().filter(e -> e.change() == Change.REMOVED).count();
        int modified = (int) events.stream().filter(e -> e.change() == Change.MODIFIED).count();
        return new Report(now, firstRun, current.size(), added, removed, modified, events, libraryReport.created(),
                libraryReport.updated(), libraryReport.invalid());
    }

    private static Event event(Instant at, Change change, FoundSkill skill, String detail) {
        return new Event(0, at, change, skill.app(), skill.location(), skill.name(), skill.relativePath(), detail);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
