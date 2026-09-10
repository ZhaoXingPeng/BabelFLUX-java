package com.babelflux.backend.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlywayMigrationTest {
    @Test
    void migratesAnEmptySchemaAndIsRepeatable() {
        String url = "jdbc:h2:mem:flyway-empty;DB_CLOSE_DELAY=-1";
        Flyway flyway = flyway(url, false);

        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(5, jdbc(url).queryForObject("select count(*) from information_schema.tables "
                + "where upper(table_name) like 'BABELFLUX_%'", Integer.class));
        flyway.validate();
    }

    @Test
    void baselinesAnExistingSchemaWithoutReapplyingV1() {
        String url = "jdbc:h2:mem:flyway-baseline;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = jdbc(url);
        jdbc.execute("create table legacy_application_table (id integer primary key)");

        Flyway flyway = flyway(url, true);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        flyway.validate();
    }

    private static Flyway flyway(String url, boolean baselineOnMigrate) {
        return Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("1")
                .cleanDisabled(true)
                .load();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }
}
