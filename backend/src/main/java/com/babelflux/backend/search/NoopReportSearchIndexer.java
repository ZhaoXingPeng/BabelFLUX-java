package com.babelflux.backend.search;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "false", matchIfMissing = true)
public class NoopReportSearchIndexer implements ReportSearchIndexer, ReportSearchQuery {
    @Override public void index(String reportId, Map<String, Object> report) { }
    @Override public ReportSearchPage search(ReportSearchCriteria criteria) {
        return new ReportSearchPage(java.util.List.of(), 0, criteria.page(), criteria.size());
    }
}
