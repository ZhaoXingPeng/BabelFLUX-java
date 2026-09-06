package com.babelflux.backend.search;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "false", matchIfMissing = true)
public class NoopReportSearchIndexer implements ReportSearchIndexer {
    @Override public void index(String reportId, Map<String, Object> report) { }
}
