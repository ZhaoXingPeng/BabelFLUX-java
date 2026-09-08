package com.babelflux.backend.infrastructure;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies additive columns that cannot be expressed portably in schema.sql.
 * MySQL does not accept the H2-style {@code ADD COLUMN IF NOT EXISTS}; metadata
 * checks keep startup idempotent for both fresh and already-created databases.
 */
@Component
@DependsOn("dataSourceScriptDatabaseInitializer")
public class JdbcSchemaMigration {
    private final JdbcTemplate jdbc;

    public JdbcSchemaMigration(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void migrate() {
        ensureColumn("babelflux_session_event_outbox", "lease_owner", "varchar(128)");
        ensureColumn("babelflux_session_event_outbox", "lease_until", "timestamp null");
        ensureColumn("babelflux_report_index_jobs", "lease_owner", "varchar(128)");
        ensureColumn("babelflux_report_index_jobs", "lease_until", "timestamp null");
    }

    private void ensureColumn(String table, String column, String definition) {
        if (hasColumn(table, column)) return;
        try {
            jdbc.execute("alter table " + identifier(table) + " add column " + identifier(column) + " " + definition);
        } catch (DataAccessException error) {
            // Two instances may pass the metadata check at the same time. If
            // one has already added the column, the desired state is reached.
            if (!hasColumn(table, column)) throw error;
        }
    }

    private boolean hasColumn(String table, String column) {
        Boolean present = jdbc.execute((Connection connection) -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, null, null)) {
                while (columns.next()) {
                    if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
                return false;
            }
        });
        return Boolean.TRUE.equals(present);
    }

    private static String identifier(String value) {
        if (!value.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("unsafe SQL identifier");
        return value;
    }
}
