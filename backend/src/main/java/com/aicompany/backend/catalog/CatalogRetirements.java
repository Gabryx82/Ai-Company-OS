package com.aicompany.backend.catalog;

import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * PHASE 32 (TD-71): the entries of the shipped catalog that were retired. Deleting a
 * shipped software entry, library entry or model records it here, and
 * {@link CatalogBootstrap} no longer inserts it at the next start -- so the deletion
 * lasts. Restoring removes the record and puts the entry back as shipped, now.
 */
@Service
@Transactional
public class CatalogRetirements {

    public enum Catalog { SOFTWARE, RESOURCE, MODEL }

    public record Retirement(Catalog catalog, String key, String retiredBy, Instant retiredAt) {
    }

    private final JdbcTemplate jdbc;
    private final CatalogBootstrap bootstrap;
    private final SecurityLog log;

    public CatalogRetirements(JdbcTemplate jdbc, CatalogBootstrap bootstrap, SecurityLog log) {
        this.jdbc = jdbc;
        this.bootstrap = bootstrap;
        this.log = log;
    }

    /** Called in the transaction that deletes the entry. */
    public void retire(Catalog catalog, String key, String by) {
        jdbc.update("INSERT INTO catalog_retirements (catalog, key, retired_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                catalog.name(), key, by);
    }

    @Transactional(readOnly = true)
    public List<Retirement> list() {
        return jdbc.query("SELECT catalog, key, retired_by, retired_at FROM catalog_retirements ORDER BY retired_at DESC",
                (rs, n) -> new Retirement(Catalog.valueOf(rs.getString("catalog")), rs.getString("key"),
                        rs.getString("retired_by"), rs.getTimestamp("retired_at", java.util.Calendar.getInstance(
                                java.util.TimeZone.getTimeZone("UTC"))).toInstant()));
    }

    /**
     * The entry comes back as the catalog ships it -- not as it was when it was
     * retired: what an operator had changed in it left with it. No precondition:
     * the record is either there or not, and nothing the caller read decides it.
     */
    public void restore(Catalog catalog, String key, String by) {
        int removed = jdbc.update("DELETE FROM catalog_retirements WHERE catalog = ? AND key = ?", catalog.name(), key);
        if (removed == 0) {
            throw new RetirementNotFoundException(catalog.name(), key);
        }
        bootstrap.restore(catalog.name(), key);
        log.record(SecurityLog.DATA_RESTORED, by, null, "catalog " + catalog + " " + key);
    }

}
