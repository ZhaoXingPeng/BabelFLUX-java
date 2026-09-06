package com.babelflux.backend.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportIndexJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcReportIndexJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void enqueue(String reportId, java.util.Map<String, Object> document) {
        final String payload;
        try {
            payload = mapper.writeValueAsString(document);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("report index document cannot be serialized", error);
        }
        int updated = jdbc.update("update babelflux_report_index_jobs set payload=?, status='pending', attempts=0, "
                        + "next_attempt_at=current_timestamp, last_error=null, updated_at=current_timestamp where report_id=?",
                payload, reportId);
        if (updated == 0) {
            try {
                jdbc.update("insert into babelflux_report_index_jobs "
                                + "(report_id, payload, status, attempts, next_attempt_at, updated_at) "
                                + "values (?, ?, 'pending', 0, current_timestamp, current_timestamp)", reportId, payload);
            } catch (DuplicateKeyException race) {
                jdbc.update("update babelflux_report_index_jobs set payload=?, status='pending', attempts=0, "
                                + "next_attempt_at=current_timestamp, last_error=null, updated_at=current_timestamp where report_id=?",
                        payload, reportId);
            }
        }
    }

    public List<PendingJob> pending(int limit) {
        return jdbc.query("select report_id, payload, attempts from babelflux_report_index_jobs "
                        + "where status='pending' and next_attempt_at <= current_timestamp "
                        + "order by updated_at limit ?", this::map, limit);
    }

    public Optional<JobStatus> status(String reportId) {
        return jdbc.query("select report_id, status, attempts, next_attempt_at, last_error, updated_at "
                        + "from babelflux_report_index_jobs where report_id=?", this::mapStatus, reportId)
                .stream().findFirst();
    }

    public void markIndexed(String reportId) {
        jdbc.update("update babelflux_report_index_jobs set status='indexed', updated_at=current_timestamp, "
                + "last_error=null where report_id=?", reportId);
    }

    public void markFailed(String reportId, Instant nextAttemptAt, String error) {
        String detail = error == null ? "unknown indexing failure" : error;
        if (detail.length() > 1000) detail = detail.substring(0, 1000);
        jdbc.update("update babelflux_report_index_jobs set attempts=attempts+1, next_attempt_at=?, "
                        + "last_error=?, updated_at=current_timestamp where report_id=?", Timestamp.from(nextAttemptAt), detail,
                reportId);
    }

    private PendingJob map(ResultSet row, int ignored) throws SQLException {
        return new PendingJob(row.getString("report_id"), row.getString("payload"), row.getInt("attempts"));
    }

    private JobStatus mapStatus(ResultSet row, int ignored) throws SQLException {
        Timestamp next = row.getTimestamp("next_attempt_at");
        Timestamp updated = row.getTimestamp("updated_at");
        return new JobStatus(row.getString("report_id"), row.getString("status"), row.getInt("attempts"),
                next == null ? null : next.toInstant(), row.getString("last_error"),
                updated == null ? null : updated.toInstant());
    }

    public record PendingJob(String reportId, String payload, int attempts) {}
    public record JobStatus(String reportId, String status, int attempts, Instant nextAttemptAt,
                            String lastError, Instant updatedAt) {}
}
