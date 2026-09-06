package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Relational audit adapter; full aggregate persistence is a later migration slice. */
@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "mysql-enabled", havingValue = "true")
public class JdbcSessionAuditStore {
    private final JdbcTemplate jdbc;

    public JdbcSessionAuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void recordCreated(Session session) {
        jdbc.update("insert into babelflux_session_audit(session_id, status) values (?, ?)",
                session.getId(), session.getStatus());
    }
}
