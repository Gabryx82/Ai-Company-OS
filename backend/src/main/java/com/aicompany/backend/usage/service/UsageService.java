package com.aicompany.backend.usage.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.usage.exception.QuotaPlanNotFoundException;
import com.aicompany.backend.usage.local.ClaudeCodeUsage;
import com.aicompany.backend.usage.local.CodexRateLimits;
import com.aicompany.backend.usage.model.QuotaPlan;
import com.aicompany.backend.usage.model.WindowKind;
import com.aicompany.backend.usage.repository.QuotaPlanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The usage view: for every quota window, how much is used and when it resets.
 *
 * <p>Every figure says where it came from ({@link Status}). A number is shown
 * only when something measured it; a reset time is computed only from a
 * measurement or from an anchor the operator configured. Nothing is guessed.
 */
@Service
@Transactional
public class UsageService {

    public enum Status {
        /** The vendor's own client measured it (Codex rate limits). */
        MEASURED,
        /** Counted from local records (tokens), with no limit to compare against. */
        COUNTED,
        /** The last measurement is older than its window: the window has reset since. */
        RESET_SINCE_OBSERVATION,
        /** The source exists and holds nothing for this window. */
        NO_DATA,
        /** A reset cannot be computed until the operator states when it happens. */
        CONFIGURATION_NEEDED
    }

    public record ModelTokens(String model, long tokens) {
    }

    public record Window(QuotaPlan plan, Status status, Double usedPercent, Long tokens, Long outputTokens,
                         Instant windowStart, Instant nextResetAt, Instant observedAt, String detail,
                         List<ModelTokens> byModel) {
    }

    private static final Duration CACHE = Duration.ofSeconds(30);

    private final QuotaPlanRepository plans;
    private final JdbcTemplate jdbc;
    private final CodexRateLimits codex;
    private final ClaudeCodeUsage claude;
    private final Clock clock;

    private volatile List<ClaudeCodeUsage.Entry> claudeEntries;
    private volatile Instant claudeReadAt = Instant.MIN;

    public UsageService(QuotaPlanRepository plans, JdbcTemplate jdbc, CodexRateLimits codex, ClaudeCodeUsage claude,
                        Clock clock) {
        this.plans = plans;
        this.jdbc = jdbc;
        this.codex = codex;
        this.claude = claude;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Window> report() {
        Instant now = clock.instant();
        Optional<CodexRateLimits.Snapshot> codexSnapshot = Optional.empty();
        boolean codexRead = false;
        List<Window> out = new ArrayList<>();
        for (QuotaPlan plan : plans.findAllByOrderBySubjectAscKeyAsc()) {
            switch (plan.getSource()) {
                case CODEX_LOCAL -> {
                    if (!codexRead) {
                        codexSnapshot = codex.latest();
                        codexRead = true;
                    }
                    out.add(codexWindow(plan, codexSnapshot, now));
                }
                case CLAUDE_CODE_LOCAL -> out.add(claudeWindow(plan, now));
                case ENGINE_RUNS -> out.add(engineWindow(plan, now));
                case MANUAL -> out.add(manualWindow(plan, now));
            }
        }
        return out;
    }

    public QuotaPlan anchor(String key, Integer weekday, String time, String zone, String limitNote,
                            Precondition precondition) {
        LocalTime resetTime = null;
        if (time != null && !time.isBlank()) {
            try {
                resetTime = LocalTime.parse(time);
            } catch (DateTimeException e) {
                throw new RequestValidationException("resetTime", "must be HH:mm");
            }
        }
        if (zone != null && !zone.isBlank()) {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                throw new RequestValidationException("resetZone", "must be an IANA zone such as Europe/Rome");
            }
        }
        QuotaPlan plan = plans.findByKeyForUpdate(key).orElseThrow(() -> new QuotaPlanNotFoundException(key));
        precondition.requireSatisfiedBy(plan.getVersion());
        plan.anchor(weekday, resetTime, zone == null || zone.isBlank() ? null : zone, limitNote);
        plans.flush();
        return plan;
    }

    // --- sources -------------------------------------------------------------

    private Window codexWindow(QuotaPlan plan, Optional<CodexRateLimits.Snapshot> snapshot, Instant now) {
        int minutes = plan.getWindowKind() == WindowKind.ROLLING_5H ? 300 : 10_080;
        Optional<CodexRateLimits.Window> window = snapshot.flatMap(s -> s.windows().stream()
                .filter(w -> w.windowMinutes() == minutes).findFirst());
        if (window.isEmpty()) {
            return new Window(plan, Status.NO_DATA, null, null, null, null, null, null,
                    "No Codex session on this machine has recorded this window yet", List.of());
        }
        CodexRateLimits.Window w = window.get();
        Instant observed = snapshot.get().observedAt();
        Instant start = w.resetsAt().minus(Duration.ofMinutes(w.windowMinutes()));
        if (!now.isBefore(w.resetsAt())) {
            return new Window(plan, Status.RESET_SINCE_OBSERVATION, 0.0, null, null, null, null, observed,
                    "Last observed at %.0f%% before the reset of %s; no Codex session since"
                            .formatted(w.usedPercent(), w.resetsAt()), List.of());
        }
        return new Window(plan, Status.MEASURED, w.usedPercent(), null, null, start, w.resetsAt(), observed,
                snapshot.get().planType() == null ? null : "Plan: " + snapshot.get().planType(), List.of());
    }

    private Window claudeWindow(QuotaPlan plan, Instant now) {
        List<ClaudeCodeUsage.Entry> entries = claudeEntries(now);
        if (plan.getWindowKind() == WindowKind.ROLLING_5H) {
            Optional<ClaudeCodeUsage.Block> block = ClaudeCodeUsage.activeBlock(entries, now);
            if (block.isEmpty()) {
                return new Window(plan, Status.NO_DATA, null, 0L, 0L, null, null, now,
                        "No five-hour window is open: the next one starts with the next message", List.of());
            }
            ClaudeCodeUsage.Block b = block.get();
            return counted(plan, b.entries(), b.start(), b.end(), now,
                    "Window reconstructed from local logs: it opened at the hour of its first message");
        }
        Optional<Instant[]> bounds = anchoredBounds(plan, now);
        if (bounds.isEmpty()) {
            Instant start = now.minus(Duration.ofDays(7));
            List<ClaudeCodeUsage.Entry> week = entries.stream().filter(e -> !e.at().isBefore(start)).toList();
            Window rolling = counted(plan, week, start, null, now,
                    "Last 7 days. Set the weekly reset (Claude settings > Usage) to count the real window");
            return new Window(plan, Status.CONFIGURATION_NEEDED, null, rolling.tokens(), rolling.outputTokens(),
                    start, null, now, rolling.detail(), rolling.byModel());
        }
        Instant start = bounds.get()[0];
        return counted(plan, entries.stream().filter(e -> !e.at().isBefore(start)).toList(),
                start, bounds.get()[1], now, null);
    }

    private Window engineWindow(QuotaPlan plan, Instant now) {
        Instant[] bounds = anchoredBounds(plan, now).orElseGet(() -> naturalBounds(plan.getWindowKind(), now));
        Map<String, long[]> byModel = new LinkedHashMap<>();
        jdbc.query("""
                SELECT served_model, COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0)
                  FROM task_runs
                 WHERE served_model LIKE ? AND created_at >= ?
                 GROUP BY served_model ORDER BY served_model""",
                rs -> {
                    byModel.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
                },
                plan.getSubject() + ":%", Timestamp.from(bounds[0]));
        long total = byModel.values().stream().mapToLong(v -> v[0] + v[1]).sum();
        long output = byModel.values().stream().mapToLong(v -> v[1]).sum();
        return new Window(plan, total == 0 ? Status.NO_DATA : Status.COUNTED, null, total, output, bounds[0],
                bounds[1], now, "Runs this control plane sent to '" + plan.getSubject() + "'",
                byModel.entrySet().stream().map(e -> new ModelTokens(e.getKey(), e.getValue()[0] + e.getValue()[1]))
                        .toList());
    }

    private Window manualWindow(QuotaPlan plan, Instant now) {
        Optional<Instant[]> bounds = anchoredBounds(plan, now);
        return bounds
                .map(b -> new Window(plan, Status.NO_DATA, null, null, null, b[0], b[1], now,
                        "Nothing on this machine measures this window; check the provider's usage page", List.of()))
                .orElseGet(() -> new Window(plan, Status.CONFIGURATION_NEEDED, null, null, null, null, null, now,
                        "Set when this window resets", List.of()));
    }

    private Window counted(QuotaPlan plan, List<ClaudeCodeUsage.Entry> entries, Instant start, Instant end,
                           Instant now, String detail) {
        Map<String, Long> byModel = new LinkedHashMap<>();
        long output = 0;
        for (ClaudeCodeUsage.Entry entry : entries) {
            byModel.merge(entry.model(), entry.total(), Long::sum);
            output += entry.output();
        }
        long total = byModel.values().stream().mapToLong(Long::longValue).sum();
        return new Window(plan, entries.isEmpty() ? Status.NO_DATA : Status.COUNTED, null, total, output, start, end,
                now, detail, byModel.entrySet().stream().map(e -> new ModelTokens(e.getKey(), e.getValue())).toList());
    }

    private List<ClaudeCodeUsage.Entry> claudeEntries(Instant now) {
        List<ClaudeCodeUsage.Entry> cached = claudeEntries;
        if (cached != null && claudeReadAt.plus(CACHE).isAfter(now)) {
            return cached;
        }
        List<ClaudeCodeUsage.Entry> read = claude.entriesSince(now.minus(Duration.ofDays(8)));
        claudeEntries = read;
        claudeReadAt = now;
        return read;
    }

    // --- reset arithmetic ----------------------------------------------------

    /** The window containing {@code now}, from the operator's anchor, if the plan has one. */
    static Optional<Instant[]> anchoredBounds(QuotaPlan plan, Instant now) {
        ZoneId zone = plan.getResetZone() == null ? ZoneId.systemDefault() : ZoneId.of(plan.getResetZone());
        LocalTime time = plan.getResetTime();
        ZonedDateTime local = now.atZone(zone);
        return switch (plan.getWindowKind()) {
            case WEEKLY -> {
                if (plan.getResetWeekday() == null || time == null) {
                    yield Optional.empty();
                }
                ZonedDateTime start = local.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(plan.getResetWeekday())))
                        .with(time);
                if (start.isAfter(local)) {
                    start = start.minusWeeks(1);
                }
                yield Optional.of(new Instant[]{start.toInstant(), start.plusWeeks(1).toInstant()});
            }
            case DAILY -> {
                if (time == null) {
                    yield Optional.empty();
                }
                ZonedDateTime start = local.with(time);
                if (start.isAfter(local)) {
                    start = start.minusDays(1);
                }
                yield Optional.of(new Instant[]{start.toInstant(), start.plusDays(1).toInstant()});
            }
            case MONTHLY -> {
                if (time == null) {
                    yield Optional.empty();
                }
                ZonedDateTime start = local.withDayOfMonth(1).with(time);
                if (start.isAfter(local)) {
                    start = start.minusMonths(1);
                }
                yield Optional.of(new Instant[]{start.toInstant(), start.plusMonths(1).toInstant()});
            }
            case ROLLING_5H -> Optional.empty();
        };
    }

    private static Instant[] naturalBounds(WindowKind kind, Instant now) {
        ZonedDateTime local = now.atZone(ZoneId.systemDefault());
        return switch (kind) {
            case DAILY -> {
                ZonedDateTime start = local.truncatedTo(ChronoUnit.DAYS);
                yield new Instant[]{start.toInstant(), start.plusDays(1).toInstant()};
            }
            case WEEKLY -> {
                ZonedDateTime start = local.truncatedTo(ChronoUnit.DAYS)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Instant[]{start.toInstant(), start.plusWeeks(1).toInstant()};
            }
            case MONTHLY -> {
                ZonedDateTime start = local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
                yield new Instant[]{start.toInstant(), start.plusMonths(1).toInstant()};
            }
            case ROLLING_5H -> new Instant[]{now.minus(Duration.ofHours(5)), null};
        };
    }
}
