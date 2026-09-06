package com.babelflux.backend.search;

import java.time.Instant;

public record ReportSearchCriteria(String query, String sourceLanguage, String domain,
                                   Instant from, Instant to, int page, int size) {
    public ReportSearchCriteria {
        page = Math.max(0, page);
        size = Math.min(100, Math.max(1, size));
    }
}
