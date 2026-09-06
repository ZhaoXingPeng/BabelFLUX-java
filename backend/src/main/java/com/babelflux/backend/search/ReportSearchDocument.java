package com.babelflux.backend.search;

import com.babelflux.backend.domain.SessionReport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class ReportSearchDocument {
    private ReportSearchDocument() {}

    static Map<String, Object> from(SessionReport report) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("reportId", report.reportId());
        document.put("sessionId", report.sessionId());
        document.put("sessionName", report.sessionName());
        document.put("productMode", report.productMode());
        document.put("inputMode", report.inputMode());
        document.put("sourceLabel", report.sourceLabel());
        document.put("domain", report.domain());
        document.put("modelProfile", report.modelProfile());
        document.put("sourceLanguage", report.sourceLanguage());
        document.put("targetLanguage", report.targetLanguage());
        document.put("generatedAt", report.generatedAt());
        document.put("summary", report.summary());
        document.put("qualityNotes", report.qualityNotes());
        document.put("schemaVersion", 1);
        String segments = report.segments().stream()
                .map(segment -> value(segment.sourceText()) + " " + value(segment.finalTranslation()))
                .collect(Collectors.joining(" "));
        document.put("searchText", String.join(" ", value(report.sessionName()), value(report.summary()), segments));
        return document;
    }

    private static String value(String value) { return value == null ? "" : value; }
}
