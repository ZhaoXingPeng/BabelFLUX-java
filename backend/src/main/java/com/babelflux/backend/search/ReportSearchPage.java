package com.babelflux.backend.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReportSearchPage(List<Hit> items, long total, int page, int size) {
    public record Hit(String reportId, String sessionId, String sessionName,
                      String sourceLanguage, String targetLanguage, String domain,
                      Instant generatedAt, String summary, Map<String, Object> source) {}
}
