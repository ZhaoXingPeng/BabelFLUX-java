package com.babelflux.backend.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs only against a fresh, isolated MySQL schema supplied by the caller. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_FLYWAY_IT", matches = "true")
class FlywayMysqlMigrationIntegrationTest {
    @Test
    void migratorCreatesSchemaAndApplicationAccountCannotExecuteDdl() {
        String url = required("TEST_MYSQL_URL");
        Flyway flyway = Flyway.configure()
                .dataSource(url, required("TEST_MYSQL_MIGRATOR_USERNAME"), required("TEST_MYSQL_MIGRATOR_PASSWORD"))
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();

        JdbcTemplate application = new JdbcTemplate(new DriverManagerDataSource(url,
                required("TEST_MYSQL_APP_USERNAME"), required("TEST_MYSQL_APP_PASSWORD")));
        String sessionId = "flyway-it-" + UUID.randomUUID();
        application.update("insert into babelflux_session_audit (session_id, status) values (?, ?)",
                sessionId, "migrated");
        assertEquals("migrated", application.queryForObject("select status from babelflux_session_audit "
                + "where session_id=?", String.class, sessionId));
        assertThrows(DataAccessException.class, () -> application.execute(
                "create table babelflux_application_privilege_probe (id integer)"));
        application.update("delete from babelflux_session_audit where session_id=?", sessionId);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the isolated MySQL Flyway integration test");
        }
        return value;
    }
}
