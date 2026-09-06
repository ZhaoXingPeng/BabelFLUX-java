package com.babelflux.backend.search;

import com.babelflux.backend.domain.SessionReport;

public interface ReportIndexingPort {
    void enqueue(SessionReport report);
    JdbcReportIndexJobStore.JobStatus status(String reportId);
    int rebuild();
}
