package com.babelflux.backend.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcSchemaMigrationTest {
    @Test
    void schemaScriptDoesNotUseUnsupportedAddColumnIfNotExists() throws IOException {
        String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        assertFalse(schema.toLowerCase().contains("add column if not exists"));
    }

    @Test
    void addsMissingOutboxAndIndexJobColumnsAndIsIdempotent() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:schema-migration;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_session_event_outbox (event_id varchar(64) primary key)");
        jdbc.execute("create table babelflux_report_index_jobs (report_id varchar(128) primary key)");

        JdbcSchemaMigration migration = new JdbcSchemaMigration(jdbc);
        migration.migrate();
        migration.migrate();

        assertEquals(4, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where upper(table_name)='BABELFLUX_SESSION_EVENT_OUTBOX'", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where upper(table_name)='BABELFLUX_REPORT_INDEX_JOBS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where upper(table_name)='BABELFLUX_SESSION_EVENT_OUTBOX' and upper(column_name)='LEASE_OWNER'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where upper(table_name)='BABELFLUX_REPORT_INDEX_JOBS' and upper(column_name)='LEASE_UNTIL'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where upper(table_name)='BABELFLUX_SESSION_EVENT_OUTBOX' and upper(column_name)='LAST_ERROR'", Integer.class));
    }
}
