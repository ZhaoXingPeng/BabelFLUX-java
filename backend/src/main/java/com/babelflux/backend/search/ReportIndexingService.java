package com.babelflux.backend.search;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.domain.SessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "true")
public class ReportIndexingService implements ReportIndexingPort {
    private static final Logger log = LoggerFactory.getLogger(ReportIndexingService.class);
    private final JdbcReportIndexJobStore jobs;
    private final ReportSearchIndexer indexer;
    private final SessionRepository sessions;
    private final ObjectMapper mapper;

    public ReportIndexingService(JdbcReportIndexJobStore jobs, ReportSearchIndexer indexer,
                                 SessionRepository sessions, ObjectMapper mapper) {
        this.jobs = jobs;
        this.indexer = indexer;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Transactional
    @Override
    public void enqueue(SessionReport report) {
        jobs.enqueue(report.reportId(), ReportSearchDocument.from(report));
    }

    @Scheduled(fixedDelayString = "${babelflux.search.relay-interval-ms:1000}")
    public void processPending() {
        for (JdbcReportIndexJobStore.PendingJob job : jobs.pending(100)) {
            try {
                indexer.index(job.reportId(), mapper.readValue(job.payload(), new TypeReference<>() {}));
                jobs.markIndexed(job.reportId());
            } catch (Exception error) {
                long backoffSeconds = Math.min(300, 1L << Math.min(job.attempts(), 8));
                jobs.markFailed(job.reportId(), Instant.now().plusSeconds(backoffSeconds), error.getMessage());
                log.warn("report index failed reportId={} attempt={} nextRetrySeconds={}",
                        job.reportId(), job.attempts() + 1, backoffSeconds, error);
            }
        }
    }

    @Override
    public JdbcReportIndexJobStore.JobStatus status(String reportId) {
        return jobs.status(reportId).orElse(null);
    }

    @Transactional
    @Override
    public int rebuild() {
        int queued = 0;
        for (Session session : sessions.findAll()) {
            SessionReport report = session.getReport();
            if (report != null) {
                enqueue(report);
                queued++;
            }
        }
        return queued;
    }
}
