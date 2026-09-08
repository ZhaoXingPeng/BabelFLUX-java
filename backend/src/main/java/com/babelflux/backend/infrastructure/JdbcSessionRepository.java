package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.domain.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC-backed aggregate store selected explicitly with MYSQL_ENABLED=true. */
@Repository
@Primary
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "mysql-enabled", havingValue = "true")
public class JdbcSessionRepository implements SessionRepository {
    private static final String COLUMNS = "session_id, created_at_epoch, ended_at_epoch, status, "
            + "session_name, source_language, target_language, domain, model_profile, product_mode, "
            + "input_mode, source_label, source_url, source_permission, tts_enabled, glossary_json, "
            + "segments_json, report_json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSessionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        String segments = writeJson(session.getSegments());
        String report = session.getReport() == null ? null : writeJson(session.getReport());
        Object[] values = values(session, segments, report);
        int updated = jdbc.update("update babelflux_sessions set created_at_epoch=?, ended_at_epoch=?, status=?, "
                        + "session_name=?, source_language=?, target_language=?, domain=?, model_profile=?, product_mode=?, "
                        + "input_mode=?, source_label=?, source_url=?, source_permission=?, tts_enabled=?, glossary_json=?, "
                        + "segments_json=?, report_json=? where session_id=?",
                valuesForUpdate(values));
        if (updated == 0) {
            try {
                jdbc.update("insert into babelflux_sessions (" + COLUMNS + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", values);
            } catch (DuplicateKeyException race) {
                jdbc.update("update babelflux_sessions set created_at_epoch=?, ended_at_epoch=?, status=?, "
                                + "session_name=?, source_language=?, target_language=?, domain=?, model_profile=?, product_mode=?, "
                                + "input_mode=?, source_label=?, source_url=?, source_permission=?, tts_enabled=?, glossary_json=?, "
                                + "segments_json=?, report_json=? where session_id=?",
                        valuesForUpdate(values));
            }
        }
        return session;
    }

    @Override
    public Optional<Session> findById(String id) {
        List<Session> sessions = jdbc.query("select " + COLUMNS + " from babelflux_sessions where session_id=?",
                this::map, id);
        return sessions.stream().findFirst();
    }

    @Override
    public Optional<Session> findByIdForUpdate(String id) {
        List<Session> sessions = jdbc.query("select " + COLUMNS
                        + " from babelflux_sessions where session_id=? for update", this::map, id);
        return sessions.stream().findFirst();
    }

    @Override
    public List<Session> findAll() {
        return jdbc.query("select " + COLUMNS + " from babelflux_sessions order by created_at_epoch desc", this::map);
    }

    @Override
    public boolean deleteById(String id) {
        return jdbc.update("delete from babelflux_sessions where session_id=?", id) > 0;
    }

    private Object[] values(Session session, String segments, String report) {
        return new Object[]{session.getId(), session.getCreatedAt().toEpochMilli(), epoch(session.getEndedAt()),
                session.getStatus(), session.getSessionName(), session.getSourceLanguage(), session.getTargetLanguage(),
                session.getDomain(), session.getModelProfile(), session.getProductMode(), session.getInputMode(),
                session.getSourceLabel(), session.getSourceUrl(), session.getSourcePermission(), session.isTtsEnabled(),
                writeJson(session.getGlossary()), segments, report};
    }

    private static Object[] valuesForUpdate(Object[] values) {
        Object[] update = new Object[values.length];
        System.arraycopy(values, 1, update, 0, values.length - 1);
        update[update.length - 1] = values[0];
        return update;
    }

    private Session map(ResultSet row, int ignored) throws SQLException {
        Instant createdAt = Instant.ofEpochMilli(row.getLong("created_at_epoch"));
        long endedEpoch = row.getLong("ended_at_epoch");
        Instant endedAt = row.wasNull() ? null : Instant.ofEpochMilli(endedEpoch);
        return Session.restore(row.getString("session_id"), createdAt, endedAt, row.getString("status"),
                row.getString("session_name"), row.getString("source_language"), row.getString("target_language"),
                row.getString("domain"), row.getString("model_profile"), row.getString("product_mode"),
                row.getString("input_mode"), row.getString("source_label"), row.getString("source_url"),
                row.getString("source_permission"), row.getBoolean("tts_enabled"),
                readGlossary(row.getString("glossary_json")), readSegments(row.getString("segments_json")),
                readReport(row.getString("report_json")));
    }

    private List<Session.GlossaryTerm> readGlossary(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, Session.GlossaryTerm.class);
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored session glossary is invalid", error);
        }
    }

    private List<Session.Segment> readSegments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, Session.Segment.class);
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored session segments are invalid", error);
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
