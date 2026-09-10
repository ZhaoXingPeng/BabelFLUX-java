package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.domain.SessionRepository;
import com.babelflux.backend.infrastructure.mybatis.SessionPersistenceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** MyBatis-backed aggregate store selected explicitly with MYSQL_ENABLED=true. */
@Repository
@Primary
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "mysql-enabled", havingValue = "true")
public class MyBatisSessionRepository implements SessionRepository {
    private final SessionPersistenceMapper sessions;
    private final ObjectMapper mapper;

    public MyBatisSessionRepository(SessionPersistenceMapper sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        SessionPersistenceMapper.Row row = row(session);
        if (sessions.update(row) == 0) {
            try {
                sessions.insert(row);
            } catch (DuplicateKeyException race) {
                sessions.update(row);
            }
        }
        return session;
    }

    @Override
    public Optional<Session> findById(String id) { return first(sessions.findById(id)); }

    @Override
    public Optional<Session> findByIdForUpdate(String id) { return first(sessions.findByIdForUpdate(id)); }

    @Override
    public List<Session> findAll() { return sessions.findAll().stream().map(this::session).toList(); }

    @Override
    public boolean deleteById(String id) { return sessions.deleteById(id) > 0; }

    private Optional<Session> first(List<SessionPersistenceMapper.Row> rows) {
        return rows.stream().findFirst().map(this::session);
    }

    private SessionPersistenceMapper.Row row(Session session) {
        SessionPersistenceMapper.Row row = new SessionPersistenceMapper.Row();
        row.setSessionId(session.getId());
        row.setCreatedAtEpoch(session.getCreatedAt().toEpochMilli());
        row.setEndedAtEpoch(epoch(session.getEndedAt()));
        row.setStatus(session.getStatus());
        row.setSessionName(session.getSessionName());
        row.setSourceLanguage(session.getSourceLanguage());
        row.setTargetLanguage(session.getTargetLanguage());
        row.setDomain(session.getDomain());
        row.setModelProfile(session.getModelProfile());
        row.setProductMode(session.getProductMode());
        row.setInputMode(session.getInputMode());
        row.setSourceLabel(session.getSourceLabel());
        row.setSourceUrl(session.getSourceUrl());
        row.setSourcePermission(session.getSourcePermission());
        row.setTtsEnabled(session.isTtsEnabled());
        row.setGlossaryJson(writeJson(session.getGlossary()));
        row.setSegmentsJson(writeJson(session.getSegments()));
        row.setReportJson(session.getReport() == null ? null : writeJson(session.getReport()));
        return row;
    }

    private Session session(SessionPersistenceMapper.Row row) {
        Instant createdAt = Instant.ofEpochMilli(row.getCreatedAtEpoch());
        Instant endedAt = row.getEndedAtEpoch() == null ? null : Instant.ofEpochMilli(row.getEndedAtEpoch());
        return Session.restore(row.getSessionId(), createdAt, endedAt, row.getStatus(), row.getSessionName(),
                row.getSourceLanguage(), row.getTargetLanguage(), row.getDomain(), row.getModelProfile(),
                row.getProductMode(), row.getInputMode(), row.getSourceLabel(), row.getSourceUrl(),
                row.getSourcePermission(), row.isTtsEnabled(), readGlossary(row.getGlossaryJson()),
                readSegments(row.getSegmentsJson()), readReport(row.getReportJson()));
    }

    private List<Session.GlossaryTerm> readGlossary(String json) {
        return readList(json, Session.GlossaryTerm.class, "stored session glossary is invalid");
    }

    private List<Session.Segment> readSegments(String json) {
        return readList(json, Session.Segment.class, "stored session segments are invalid");
    }

    private <T> List<T> readList(String json, Class<T> itemType, String message) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, itemType);
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(message, error);
        }
    }

    private SessionReport readReport(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, SessionReport.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored session report is invalid", error);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("session state cannot be serialized", error);
        }
    }

    private static Long epoch(Instant value) { return value == null ? null : value.toEpochMilli(); }
}
