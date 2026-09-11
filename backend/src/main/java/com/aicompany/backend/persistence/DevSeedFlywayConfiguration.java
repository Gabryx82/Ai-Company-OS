package com.aicompany.backend.persistence;

import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Applies the development seed, and only under the {@code dev} profile.
 *
 * <p>Spring Boot auto-configures a single Flyway bean for the schema stream. A
 * {@link FlywayMigrationStrategy} lets this profile run a second, independent
 * stream right after it and, crucially, still before the
 * {@code EntityManagerFactory} is built — so Hibernate validates a schema that
 * is already migrated.
 *
 * <p>Order matters and is explicit here:
 * <ol>
 *   <li>drop the pre-fix {@code V1000} row, if this database still carries it,
 *       otherwise the schema stream would fail validation before doing anything;</li>
 *   <li>migrate the schema stream;</li>
 *   <li>migrate the seed stream, which needs the tables to exist.</li>
 * </ol>
 *
 * <p>No other profile declares this bean, so {@code test} and {@code prod} run
 * the auto-configured schema migration alone and never touch the seed history.
 */
@Profile("dev")
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class DevSeedFlywayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DevSeedFlywayConfiguration.class);

    @Bean
    FlywayMigrationStrategy devSeedMigrationStrategy() {

        return schemaFlyway -> {

            Configuration configuration = schemaFlyway.getConfiguration();
            DataSource dataSource = configuration.getDataSource();
            String schema = defaultSchemaOf(configuration);

            if (DevSeedFlyway.removeLegacySeedHistory(dataSource, schema)) {
                log.warn("Removed the legacy {} entry from {}: the development seed now lives in "
                                + "its own Flyway stream ({}). Seeded rows were left untouched.",
                        DevSeedFlyway.LEGACY_SEED_SCRIPT,
                        DevSeedFlyway.SCHEMA_HISTORY_TABLE,
                        DevSeedFlyway.HISTORY_TABLE);
            }

            schemaFlyway.migrate();

            var seeded = DevSeedFlyway.apply(dataSource, schema);
            log.info("Development seed stream up to date: {} migration(s) applied, schema version {}",
                    seeded.migrationsExecuted, seeded.targetSchemaVersion);
        };
    }

    private static String defaultSchemaOf(Configuration configuration) {

        if (configuration.getDefaultSchema() != null) {
            return configuration.getDefaultSchema();
        }

        String[] schemas = configuration.getSchemas();
        return schemas != null && schemas.length > 0 ? schemas[0] : null;
    }
}
