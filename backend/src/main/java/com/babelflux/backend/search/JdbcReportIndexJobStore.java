package com.babelflux.backend.search;

import com.babelflux.backend.infrastructure.JdbcTemporal;
import com.babelflux.backend.infrastructure.mybatis.ReportIndexJobMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportIndexJobStore {
    private final ReportIndexJobMapper statements;
    private final ObjectMapper mapper;

    public JdbcReportIndexJobStore(ReportIndexJobMapper statements, ObjectMapper mapper) {
        this.statements = statements;
        this.mapper = mapper;
    }

    public void enqueue(String reportId, java.util.Map<String, Object> document) {
        final String payload;
        try {
            payload = mapper.writeValueAsString(document);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("report index document cannot be serialized", error);
        }
        Timestamp now = JdbcTemporal.now();
        int updated = statements.resetPending(reportId, payload, now);
        if (updated == 0) {
            try {
                statements.insertPending(reportId, payload, now);
            } catch (DuplicateKeyException race) {
                statements.resetPending(reportId, payload, now);
            }
        }
    }

    public List<PendingJob> pending(int limit) {
        Timestamp now = JdbcTemporal.now();
        return statements.pending(now, limit).stream()
                .map(row -> new PendingJob(row.getReportId(), row.getPayload(), row.getAttempts()))
                .toList();
    }

    /** Claims one job so concurrent indexers do not write the same report normally. */
    public boolean tryClaim(String reportId, String owner, Instant leaseUntil) {
        Timestamp now = JdbcTemporal.now();
        return statements.tryClaim(reportId, owner, JdbcTemporal.future(leaseUntil), now) == 1;
    }

    public Optional<JobStatus> status(String reportId) {
        return statements.status(reportId).stream().findFirst().map(row -> new JobStatus(row.getReportId(),
                row.getStatus(), row.getAttempts(), instant(row.getNextAttemptAt()), row.getLastError(),
                instant(row.getUpdatedAt())));
    }

    public void markIndexed(String reportId, String owner) {
        statements.markIndexed(reportId, owner, JdbcTemporal.now());
    }

    public void markFailed(String reportId, String owner, Instant nextAttemptAt, String error) {
        String detail = error == null ? "unknown indexing failure" : error;
        if (detail.length() > 1000) detail = detail.substring(0, 1000);
        Instant reference = Instant.now();
        statements.markFailed(reportId, owner, JdbcTemporal.dueAt(nextAttemptAt, reference), detail,
                JdbcTemporal.from(reference));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record PendingJob(String reportId, String payload, int attempts) {}
    public record JobStatus(String reportId, String status, int attempts, Instant nextAttemptAt,
                            String lastError, Instant updatedAt) {}
}
