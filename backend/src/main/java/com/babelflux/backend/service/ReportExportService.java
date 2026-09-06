package com.babelflux.backend.service;

import com.babelflux.backend.domain.SessionReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ReportExportService {
    private final ObjectMapper mapper;

    public ReportExportService(ObjectMapper mapper) { this.mapper = mapper; }

    public ExportedReport export(SessionReport report, String requestedFormat) {
        String format = requestedFormat == null || requestedFormat.isBlank()
                ? "txt" : requestedFormat.toLowerCase(Locale.ROOT);
        return switch (format) {
            case "txt" -> new ExportedReport(renderTxt(report), "txt");
            case "srt" -> new ExportedReport(renderSrt(report), "srt");
            case "md" -> new ExportedReport(renderMarkdown(report), "md");
            case "json" -> new ExportedReport(renderJson(report), "json");
            default -> throw new UnsupportedReportFormatException(format);
        };
    }

    private String renderTxt(SessionReport report) {
        StringBuilder body = new StringBuilder()
                .append("# ").append(report.sessionName()).append('\n')
                .append("领域：").append(report.domain()).append("  |  语言：")
                .append(report.sourceLanguage()).append(" -> ").append(report.targetLanguage())
                .append("  |  时长：").append(report.durationText()).append('\n')
                .append("生成时间：").append(report.generatedAt()).append('\n')
                .append("全文纠偏：").append(correctionLabel(report)).append("\n\n")
                .append("【摘要】\n").append(report.summary()).append("\n\n")
                .append("【双语终稿】\n");
        for (SessionReport.Segment segment : safe(report.segments())) {
            body.append('[').append(segment.timecode()).append("] ")
                    .append(segment.sourceText()).append('\n')
                    .append("          ").append(segment.finalTranslation()).append('\n');
        }
        appendRevisions(body, report);
        body.append("\n【质量说明】\n").append(report.qualityNotes()).append('\n');
        return body.toString();
    }

    private String renderSrt(SessionReport report) {
        StringBuilder body = new StringBuilder();
        int index = 1;
        for (SessionReport.Segment segment : safe(report.segments())) {
            long end = segment.endMs() > segment.startMs() ? segment.endMs() : segment.startMs() + 2000L;
            body.append(index++).append('\n')
                    .append(formatSrtTime(segment.startMs())).append(" --> ")
                    .append(formatSrtTime(end)).append('\n')
                    .append(segment.sourceText()).append('\n')
                    .append(segment.finalTranslation()).append("\n\n");
        }
        return body.toString();
    }

    private String renderMarkdown(SessionReport report) {
        StringBuilder body = new StringBuilder()
                .append("# ").append(report.sessionName()).append("\n\n")
                .append("- **领域**：").append(report.domain()).append('\n')
                .append("- **语言**：").append(report.sourceLanguage()).append(" → ")
                .append(report.targetLanguage()).append('\n')
                .append("- **时长**：").append(report.durationText()).append('\n')
                .append("- **句数**：").append(report.metrics().segments())
                .append("  ｜ **实时修正**：").append(report.metrics().realtimeRevisions())
                .append("  ｜ **会后修正**：").append(report.metrics().finalRevisions()).append('\n')
                .append("- **全文纠偏**：").append(correctionLabel(report)).append("\n\n")
                .append("## 摘要\n\n").append(report.summary()).append("\n\n")
                .append("## 双语终稿\n\n")
                .append("| 时间 | 原文 | 终稿译文 |\n| --- | --- | --- |\n");
        for (SessionReport.Segment segment : safe(report.segments())) {
            body.append("| ").append(segment.timecode()).append(" | ")
                    .append(escapeCell(segment.sourceText())).append(" | ")
                    .append(escapeCell(segment.finalTranslation())).append(" |\n");
        }
        body.append("\n## 修订记录\n\n");
        appendMarkdownRevisions(body, report);
        body.append("\n## 质量说明\n\n").append(report.qualityNotes()).append('\n');
        return body.toString();
    }

    private static String correctionLabel(SessionReport report) {
        String status = report.correctionStatus() == null || report.correctionStatus().isBlank()
                ? "unknown" : report.correctionStatus();
        String model = report.correctionModel() == null || report.correctionModel().isBlank()
                ? "" : " (" + report.correctionModel() + ")";
        return status + model;
    }

    private static void appendRevisions(StringBuilder body, SessionReport report) {
        body.append("\n【修订记录】\n");
        List<SessionReport.Revision> realtime = safe(report.realtimeRevisions());
        List<SessionReport.Revision> finalRevisions = safe(report.finalRevisions());
        if (realtime.isEmpty() && finalRevisions.isEmpty()) {
            body.append("无\n");
            return;
        }
        for (SessionReport.Revision revision : realtime) appendRevision(body, revision);
        for (SessionReport.Revision revision : finalRevisions) appendRevision(body, revision);
    }

    private static void appendRevision(StringBuilder body, SessionReport.Revision revision) {
        body.append('[').append(revision.stage()).append("] ").append(revision.segmentId()).append("：")
                .append(revision.beforeText()).append(" -> ").append(revision.afterText())
                .append("（").append(revision.reason()).append("）\n");
    }

    private static void appendMarkdownRevisions(StringBuilder body, SessionReport report) {
        body.append("| 阶段 | 句段 | 原译文 | 修订后 | 原因 |\n| --- | --- | --- | --- | --- |\n");
        List<SessionReport.Revision> all = new java.util.ArrayList<>();
        all.addAll(safe(report.realtimeRevisions()));
        all.addAll(safe(report.finalRevisions()));
        if (all.isEmpty()) body.append("| - | - | - | - | 无 |\n");
        for (SessionReport.Revision revision : all) {
            body.append("| ").append(escapeCell(revision.stage())).append(" | ")
                    .append(escapeCell(revision.segmentId())).append(" | ")
                    .append(escapeCell(revision.beforeText())).append(" | ")
                    .append(escapeCell(revision.afterText())).append(" | ")
                    .append(escapeCell(revision.reason())).append(" |\n");
        }
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    private String renderJson(SessionReport report) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize report", error);
        }
    }

    private static String escapeCell(String value) { return (value == null ? "" : value).replace("|", "\\|"); }

    private static String formatSrtTime(long value) {
        long millis = Math.max(0L, value);
        long hours = millis / 3_600_000L;
        long remainder = millis % 3_600_000L;
        long minutes = remainder / 60_000L;
        remainder %= 60_000L;
        long seconds = remainder / 1000L;
        return "%02d:%02d:%02d,%03d".formatted(hours, minutes, seconds, remainder % 1000L);
    }

    public record ExportedReport(String body, String extension) {}

    public static class UnsupportedReportFormatException extends RuntimeException {
        public UnsupportedReportFormatException(String format) { super("unsupported format: " + format); }
    }
}
