package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.infrastructure.mybatis.SessionAuditMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MyBatis-backed relational audit adapter. */
@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "mysql-enabled", havingValue = "true")
public class JdbcSessionAuditStore {
    private final SessionAuditMapper statements;

    public JdbcSessionAuditStore(SessionAuditMapper statements) { this.statements = statements; }

    public void recordCreated(Session session) {
        statements.recordCreated(session.getId(), session.getStatus());
    }
}
