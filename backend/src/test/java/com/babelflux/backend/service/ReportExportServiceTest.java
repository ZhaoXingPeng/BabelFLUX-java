package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportExportServiceTest {
    @Test
    void exportsCorrectionStatusAndRevisionHistory() {
        Session session = Session.create("export", "导出测试", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        session.addSegment(new Session.Segment("s1", "API", "应用程序接口", 0, 1000, "revised"));
        session.addRevision(new Session.Revision("s1", "API", "应用程序接口", "术语", 0.9));
        SessionReport report = new SessionReportService().generate(session);
        ReportExportService exporter = new ReportExportService(new ObjectMapper());

        String txt = exporter.export(report, "txt").body();
        String markdown = exporter.export(report, "md").body();

        assertTrue(txt.contains("全文纠偏：skipped"));
        assertTrue(txt.contains("[实时] s1：API -> 应用程序接口"));
        assertTrue(markdown.contains("## 修订记录"));
        assertTrue(markdown.contains("实时"));
    }
}
