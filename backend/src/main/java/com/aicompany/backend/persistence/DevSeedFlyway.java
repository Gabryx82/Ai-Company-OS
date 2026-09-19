package com.aicompany.backend.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The development seed as a Flyway stream of its own, independent from the
 * schema stream in {@code classpath:db/migration}.
 *
 * <p>TASK-001 shipped the seed as {@code V1000} inside the schema stream. That
 * pushed a seeded development database to version 1000, so a later {@code V2}
 * was an out-of-order version and {@code migrate} refused to run it. Keeping the
 * seed in a separate location <em>and</em> a separate schema history table
 * removes the coupling in both directions:
 *
 * <ul>
 *   <li>the schema stream stays linear — {@code V1}, {@code V2}, … — whether or
 *       not the database was ever seeded;</li>
 *   <li>a profile that does not apply the seed never sees seed rows in its own
 *       history, so it neither tries to run them nor reports them as missing.</li>
 * </ul>
 *
 * <p>This class is deliberately free of Spring annotations: the migration tests
 * drive it directly against an isolated PostgreSQL schema, without a Spring
 * context.
 */
public final class DevSeedFlyway {

    /** Location of the development seed migrations. Never in the schema stream. */
    public static final String LOCATION = "classpath:db/dev";

    /** Schema history table of the seed stream. Never {@code flyway_schema_history}. */
    public static final String HISTORY_TABLE = "flyway_dev_seed_history";

    /** Schema history table of the schema stream, owned by Flyway's defaults. */
    public static final String SCHEMA_HISTORY_TABLE = "flyway_schema_history";

    /** Version the seed used to occupy inside the schema stream before this fix. */
    public static final String LEGACY_SEED_VERSION = "1000";

    /** Script name the seed used to carry inside the schema stream before this fix. */
    public static final String LEGACY_SEED_SCRIPT = "V1000__dev_seed_agents.sql";

    private DevSeedFlyway() {
    }

    /**
     * Applies the seed stream. Must run after the schema stream: the seed writes
     * into tables the schema migrations create.
     *
     * @param schema PostgreSQL schema to operate in, or {@code null} for the one
     *               the connection already resolves to
     */
    public static MigrateResult apply(DataSource dataSource, String schema) {

        Flyway seed = flyway(dataSource, schema);

        // Realign the recorded checksums before validating them.
        //
        // TD-31 forced an edit to an already-applied seed migration: V8 drops
        // agents.active, and V1__dev_seed_agents.sql named that column, so on a
        // fresh database the seed would have failed outright. Editing it changes
        // its checksum, and Flyway validates checksums on migrate -- so without
        // this call every database that had already seeded would refuse to start
        // in the dev profile.
        //
        // V4 avoided the same collision by giving the new columns a DEFAULT
        // instead of teaching this file about them. That escape does not exist
        // for a dropped column the statement names explicitly.
        //
        // WHAT THIS COSTS, AND WHY IT IS ACCEPTABLE HERE AND NOWHERE ELSE.
        // repair() rewrites the checksums of applied migrations, so it also
        // accepts an edit nobody meant to make -- it trades a loud failure for a
        // silent acceptance. That is a bad trade on the schema stream, which is
        // why this call is NOT there and must not be copied there: production
        // runs that stream, and a schema migration changing under an applied
        // database is exactly the accident Flyway's validation exists to catch.
        //
        // On this stream the calculus is different in three ways that all have
        // to hold: it is dev-only (DevSeedFlywayConfiguration binds it to the
        // dev profile), it carries demonstration data rather than schema or
        // reference data, and its single statement is idempotent by its own
        // NOT EXISTS guard -- so the worst case of an unnoticed edit is
        // different demonstration rows on a developer's machine.
        //
        // It touches metadata only: no table, column or application row. A
        // migration already applied is not re-run.
        seed.repair();

        return seed.migrate();
    }

    /**
     * Builds the seed Flyway instance without running it. Exposed so tests can
     * inspect {@code info()} without mutating the database.
     */
    public static Flyway flyway(DataSource dataSource, String schema) {

        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                // The schema stream has already created its tables by the time the
                // seed stream runs, so from the seed's point of view the schema is
                // never empty. Baselining at version 0 lets Flyway create its own
                // history table there anyway, while still applying seed V1: the
                // default baseline version of 1 would mark it as already applied.
                .baselineOnMigrate(true)
                .baselineVersion("0");

        if (schema != null) {
            configuration.schemas(schema);
        }

        return configuration.load();
    }

    /**
     * Removes the pre-fix {@code V1000} row from the <em>schema</em> history.
     *
     * <p>Renaming the file does not update a database that already applied it:
     * the schema stream would resolve {@code V1} only and report {@code 1000} as
     * applied but missing, which fails validation on every subsequent start. The
     * one row is deleted; no table, column or application row is touched, and the
     * seeded data stays exactly where it is. The seed stream then registers the
     * seed in its own history, and the {@code NOT EXISTS} guard in the seed SQL
     * keeps the replay from duplicating rows.
     *
     * <p>Scoped to development on purpose: production must never have applied
     * {@code V1000} in the first place, and silently editing a production schema
     * history would hide a real deployment mistake.
     *
     * @return {@code true} when a legacy row was found and removed
     */
    public static boolean removeLegacySeedHistory(DataSource dataSource, String schema) {

        String table = qualify(schema, SCHEMA_HISTORY_TABLE);

        try (Connection connection = dataSource.getConnection()) {

            if (!tableExists(connection, table)) {
                return false;
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE version = ? AND script = ?")) {

                delete.setString(1, LEGACY_SEED_VERSION);
                delete.setString(2, LEGACY_SEED_SCRIPT);
                return delete.executeUpdate() > 0;
            }

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not inspect the Flyway schema history while looking for the legacy "
                            + LEGACY_SEED_SCRIPT + " row", e);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {

            statement.setString(1, table);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static String qualify(String schema, String table) {

        if (schema == null) {
            return quote(table);
        }
        return quote(schema) + "." + quote(table);
    }

    private static String quote(String identifier) {

        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }
}
