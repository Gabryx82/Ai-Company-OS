package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PHASE 37 (ADR-043): what stands in each tool for each agent
 * ({@code agent_publications}, V44). Plain JDBC, so not in a repository package (those
 * hold Spring Data repositories, ApiShapeGuardTest).
 */
@Component
public class AgentPublications {

    /**
     * @param fingerprint what was written, or null for an agent the tool had before
     * @param owned       false for an agent the tool had and the operator brought in
     */
    public record Publication(Long agentId, String tool, String externalId, @Nullable String fingerprint, boolean owned,
                              Instant publishedAt) {
    }

    private final JdbcTemplate jdbc;

    public AgentPublications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Publication> all() {
        return jdbc.query("SELECT agent_id, tool, external_id, fingerprint, owned, published_at FROM agent_publications "
                + "ORDER BY agent_id, tool", (row, n) -> new Publication(row.getLong("agent_id"), row.getString("tool"),
                row.getString("external_id"), row.getString("fingerprint"), row.getBoolean("owned"),
                row.getTimestamp("published_at").toInstant()));
    }

    public Optional<Publication> find(Long agentId, String tool) {
        return all().stream().filter(p -> p.agentId().equals(agentId) && p.tool().equals(tool)).findFirst();
    }

    /** The agent of AI Company OS that stands for this agent of the tool, if one does. */
    public Optional<Long> agentOf(String tool, String externalId) {
        return all().stream().filter(p -> p.tool().equals(tool) && p.externalId().equals(externalId))
                .map(Publication::agentId).findFirst();
    }

    public void record(Long agentId, String tool, String externalId, @Nullable String fingerprint, boolean owned, String by) {
        jdbc.update("""
                INSERT INTO agent_publications (agent_id, tool, external_id, fingerprint, owned, published_at, published_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (agent_id, tool) DO UPDATE SET external_id = EXCLUDED.external_id,
                    fingerprint = EXCLUDED.fingerprint, owned = EXCLUDED.owned, published_at = EXCLUDED.published_at,
                    published_by = EXCLUDED.published_by""",
                agentId, tool, externalId, fingerprint, owned, Timestamp.from(Instant.now()), by);
    }

    public void forget(Long agentId, String tool) {
        jdbc.update("DELETE FROM agent_publications WHERE agent_id = ? AND tool = ?", agentId, tool);
    }
}
