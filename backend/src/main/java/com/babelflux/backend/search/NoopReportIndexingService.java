package com.babelflux.backend.search;

import com.babelflux.backend.domain.SessionReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "false", matchIfMissing = true)
public class NoopReportIndexingService implements ReportIndexingPort {
    @Override public void enqueue(SessionReport report) { }
    @Override public JdbcReportIndexJobStore.JobStatus status(String reportId) { return null; }
    @Override public int rebuild() { return 0; }
}
