package com.aicompany.backend.orchestrator.service;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PHASE 35 (ADR-041): the files a handoff package asked the agent to read, with their
 * size when the handoff was written ({@code task_handoff_reads}, V42). Append-only, like
 * the handoff it belongs to; its rows go when the handoff goes. Plain JDBC, so not in a
 * repository package (those hold Spring Data repositories, ApiShapeGuardTest).
 */
@Component
public class HandoffReads {

    /** One file the package named; {@code chars} null when it was not there. */
    public record Row(String kind, String path, boolean required, @Nullable Integer chars) {
    }

    private final JdbcTemplate jdbc;

    public HandoffReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** In the caller's transaction, right after the handoff row: the rows keep the package's order. */
    public void record(Long handoffId, List<Row> rows) {
        for (int position = 0; position < rows.size(); position++) {
            Row row = rows.get(position);
            String path = row.path().length() <= 1000 ? row.path() : row.path().substring(0, 1000);
            jdbc.update("INSERT INTO task_handoff_reads (handoff_id, position, kind, path, required, chars) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", handoffId, position, row.kind(), path, row.required(), row.chars());
        }
    }
}
