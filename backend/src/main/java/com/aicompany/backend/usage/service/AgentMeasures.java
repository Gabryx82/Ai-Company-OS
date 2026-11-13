package com.aicompany.backend.usage.service;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE 35 (ADR-041, phase 1): what the agents consume, as it was measured -- per
 * run, per model, per agent, and per handoff. Read-only.
 *
 * <p>One rule runs through it: <strong>only measured values</strong>. A run's tokens
 * and durations are what the provider reported; an average is taken over the runs
 * that have the value and says how many those are; a value nobody measured is null,
 * never zero. The size of a prompt or a package is counted in characters on what was
 * sent or written, and never turned into tokens -- that would be an estimate.
 */
@Service
@Transactional(readOnly = true)
public class AgentMeasures {

    /** How many runs and handoffs are listed one by one: the most recent. */
    static final int LISTED = 50;

    public record RunMeasure(Long runId, Long taskId, String taskTitle, @Nullable Long agentId, @Nullable String agentName,
                             String model, String status, @Nullable Integer inputTokens, @Nullable Integer outputTokens,
                             @Nullable Long latencyMs, @Nullable Long providerTotalMs, @Nullable Long loadMs,
                             @Nullable Long promptEvalMs, @Nullable Long evalMs, @Nullable Integer contextWindow,
                             int systemChars, int userChars, @Nullable Instant finishedAt) {
    }

    /**
     * Runs grouped by the model that served them, or by the agent that ran them.
     * {@code timedRuns} is how many carry the provider's durations: the duration
     * averages are over those. {@code outputTokensPerSecond} is the generated tokens
     * over the generation time of the runs that measured both.
     */
    public record GroupMeasure(String key, @Nullable Long agentId, int runs, int timedRuns, long inputTokens,
                               long outputTokens, @Nullable Long avgInputTokens, @Nullable Long avgOutputTokens,
                               @Nullable Long avgProviderTotalMs, @Nullable Long avgLoadMs, @Nullable Long avgPromptEvalMs,
                               @Nullable Long avgEvalMs, @Nullable Double outputTokensPerSecond,
                               List<Integer> contextWindows, long avgSystemChars, long avgUserChars) {
    }

    /** One file a handoff package asked the agent to read; {@code chars} null when it was not there. */
    public record HandoffRead(String kind, String path, boolean required, @Nullable Integer chars) {
    }

    /**
     * A handoff, with the characters of its package and of what it asks to read.
     * Null sizes: a handoff written before the measures existed.
     */
    public record HandoffMeasure(Long handoffId, Long taskId, String taskTitle, String target, Instant createdAt,
                                 @Nullable Integer packageChars, @Nullable Integer requiredChars,
                                 @Nullable Integer optionalChars, int missingFiles, List<HandoffRead> reads) {
    }

    public record MeasuresReport(LocalDate from, LocalDate to, List<RunMeasure> runs, List<GroupMeasure> byModel,
                         List<GroupMeasure> byAgent, List<HandoffMeasure> handoffs) {
    }

    private final JdbcTemplate jdbc;

    public AgentMeasures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The runs the engine answered and the handoffs written between {@code from} and {@code to}, both included. */
    public MeasuresReport report(LocalDate from, LocalDate to) {
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        return new MeasuresReport(from, to, runs(start, end), byModel(start, end), byAgent(start, end), handoffs(start, end));
    }

    // --- runs ----------------------------------------------------------------------------

    /** A run the engine answered: it has a served model (a success, or a failure that spent tokens). */
    private static final String ANSWERED = "FROM task_runs r JOIN tasks t ON t.id = r.task_id "
            + "LEFT JOIN agents a ON a.id = r.agent_id "
            + "WHERE r.served_model IS NOT NULL AND r.finished_at >= ? AND r.finished_at < ? ";

    private List<RunMeasure> runs(Timestamp start, Timestamp end) {
        return jdbc.query("SELECT r.id, r.task_id, t.title, a.id AS agent_id, COALESCE(a.name, r.agent_name) AS agent_name, "
                        + "r.served_model, r.status, r.input_tokens, r.output_tokens, r.latency_ms, r.provider_total_ms, "
                        + "r.load_ms, r.prompt_eval_ms, r.eval_ms, r.context_window, "
                        + "char_length(r.system_prompt) AS system_chars, char_length(r.user_prompt) AS user_chars, r.finished_at "
                        + ANSWERED + "ORDER BY r.id DESC LIMIT " + LISTED,
                (rs, n) -> new RunMeasure(rs.getLong("id"), rs.getLong("task_id"), rs.getString("title"),
                        rs.getObject("agent_id", Long.class), rs.getString("agent_name"), rs.getString("served_model"),
                        rs.getString("status"), rs.getObject("input_tokens", Integer.class),
                        rs.getObject("output_tokens", Integer.class), rs.getObject("latency_ms", Long.class),
                        rs.getObject("provider_total_ms", Long.class), rs.getObject("load_ms", Long.class),
                        rs.getObject("prompt_eval_ms", Long.class), rs.getObject("eval_ms", Long.class),
                        rs.getObject("context_window", Integer.class), rs.getInt("system_chars"), rs.getInt("user_chars"),
                        instant(rs.getTimestamp("finished_at"))),
                start, end);
    }

    /** The aggregates both groupings share; every average ignores the runs that lack the value. */
    private static final String AGGREGATES = "count(*) AS runs, count(r.provider_total_ms) AS timed, "
            + "COALESCE(sum(r.input_tokens), 0) AS input, COALESCE(sum(r.output_tokens), 0) AS output, "
            + "round(avg(r.input_tokens)) AS avg_input, round(avg(r.output_tokens)) AS avg_output, "
            + "round(avg(r.provider_total_ms)) AS avg_total, round(avg(r.load_ms)) AS avg_load, "
            + "round(avg(r.prompt_eval_ms)) AS avg_prompt, round(avg(r.eval_ms)) AS avg_eval, "
            + "(sum(r.output_tokens) FILTER (WHERE r.eval_ms > 0 AND r.output_tokens IS NOT NULL))::bigint AS generated, "
            + "(sum(r.eval_ms) FILTER (WHERE r.eval_ms > 0 AND r.output_tokens IS NOT NULL))::bigint AS generating, "
            + "array_agg(DISTINCT r.context_window ORDER BY r.context_window) "
            + "FILTER (WHERE r.context_window IS NOT NULL) AS windows, "
            + "round(avg(char_length(r.system_prompt))) AS avg_system, round(avg(char_length(r.user_prompt))) AS avg_user ";

    private List<GroupMeasure> byModel(Timestamp start, Timestamp end) {
        return jdbc.query("SELECT r.served_model AS key, NULL::bigint AS agent_id, " + AGGREGATES + ANSWERED
                + "GROUP BY r.served_model ORDER BY runs DESC, key", (rs, n) -> group(rs), start, end);
    }

    private List<GroupMeasure> byAgent(Timestamp start, Timestamp end) {
        // A deleted agent's runs stay under the name it had (ADR-035), without an id.
        return jdbc.query("SELECT COALESCE(a.name, r.agent_name, '—') AS key, a.id AS agent_id, " + AGGREGATES + ANSWERED
                + "GROUP BY a.id, COALESCE(a.name, r.agent_name, '—') ORDER BY runs DESC, key", (rs, n) -> group(rs), start, end);
    }

    private static GroupMeasure group(ResultSet rs) throws SQLException {
        Long generated = rs.getObject("generated", Long.class);
        Long generating = rs.getObject("generating", Long.class);
        Double perSecond = generated == null || generating == null || generating == 0 ? null
                : BigDecimal.valueOf(generated * 1000.0 / generating).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new GroupMeasure(rs.getString("key"), rs.getObject("agent_id", Long.class), rs.getInt("runs"),
                rs.getInt("timed"), rs.getLong("input"), rs.getLong("output"), whole(rs, "avg_input"),
                whole(rs, "avg_output"), whole(rs, "avg_total"), whole(rs, "avg_load"), whole(rs, "avg_prompt"),
                whole(rs, "avg_eval"), perSecond, integers(rs.getArray("windows")),
                orZero(whole(rs, "avg_system")), orZero(whole(rs, "avg_user")));
    }

    // --- handoffs ------------------------------------------------------------------------

    private List<HandoffMeasure> handoffs(Timestamp start, Timestamp end) {
        Map<Long, List<HandoffRead>> reads = new LinkedHashMap<>();
        jdbc.query("SELECT handoff_id, kind, path, required, chars FROM task_handoff_reads WHERE handoff_id IN "
                        + "(SELECT id FROM task_handoffs WHERE created_at >= ? AND created_at < ? ORDER BY id DESC LIMIT "
                        + LISTED + ") ORDER BY handoff_id, position",
                (ResultSet rs) -> {
                    reads.computeIfAbsent(rs.getLong("handoff_id"), id -> new ArrayList<>()).add(new HandoffRead(
                            rs.getString("kind"), rs.getString("path"), rs.getBoolean("required"),
                            rs.getObject("chars", Integer.class)));
                }, start, end);
        return jdbc.query("SELECT h.id, h.task_id, t.title, h.target, h.created_at, h.package_chars FROM task_handoffs h "
                        + "JOIN tasks t ON t.id = h.task_id WHERE h.created_at >= ? AND h.created_at < ? "
                        + "ORDER BY h.id DESC LIMIT " + LISTED,
                (rs, n) -> {
                    long id = rs.getLong("id");
                    List<HandoffRead> asked = reads.getOrDefault(id, List.of());
                    Integer packageChars = rs.getObject("package_chars", Integer.class);
                    return new HandoffMeasure(id, rs.getLong("task_id"), rs.getString("title"), rs.getString("target"),
                            instant(rs.getTimestamp("created_at")), packageChars,
                            packageChars == null ? null : sum(asked, true), packageChars == null ? null : sum(asked, false),
                            (int) asked.stream().filter(r -> r.chars() == null).count(), List.copyOf(asked));
                }, start, end);
    }

    /** The characters of the files that were there, among the required (or the optional) ones. */
    private static int sum(List<HandoffRead> reads, boolean required) {
        return reads.stream().filter(r -> r.required() == required && r.chars() != null).mapToInt(HandoffRead::chars).sum();
    }

    // --- helpers -------------------------------------------------------------------------

    private static @Nullable Long whole(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.longValue();
    }

    private static long orZero(@Nullable Long value) {
        return value == null ? 0 : value;
    }

    private static List<Integer> integers(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object values = array.getArray();
        return values instanceof Integer[] ints ? List.copyOf(Arrays.asList(ints)) : List.of();
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
