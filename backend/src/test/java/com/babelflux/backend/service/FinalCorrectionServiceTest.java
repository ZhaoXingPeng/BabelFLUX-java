package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinalCorrectionServiceTest {
    @Test
    void parsesCompleteCorrectionAndBuildsRevisionMetadata() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-1", "qwen-plus", "{" +
                        "\"summary\":\"summary\",\"qualityNotes\":\"checked\",\"glossaryHits\":[" +
                        "{\"term\":\"API\",\"translation\":\"接口\"}],\"segments\":[" +
                        "{\"id\":\"s1\",\"finalTranslation\":\"修正译文\"}," +
                        "{\"id\":\"s2\",\"finalTranslation\":\"保持译文\"}],\"revisions\":[" +
                        "{\"id\":\"s1\",\"after\":\"修正译文\",\"reason\":\"术语\"}]}" ,
                List.of(), "stop", Map.of()));
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());
        Session session = session();

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("completed", result.status());
        assertEquals("修正译文", result.finalById().get("s1"));
        assertEquals(1, result.revisions().size());
        assertEquals("接口", result.glossaryHits().getFirst().translation());
        service.shutdown();
    }

    @Test
    void reportsTimeoutWithoutFailingTheBaseReport() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        properties.setFinalCorrectionTimeout(Duration.ofMillis(20));
        when(client.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return null;
        });
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());

        Session session = session();
        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("timeout", result.status());
        assertEquals("qwen-plus", result.model());
        assertTrue(result.error().contains("超时"));
        service.shutdown();
    }

    @Test
    void rejectsSignificantlyShorterLongFormCorrection() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-2", "qwen-plus", "{" +
                        "\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"简短译文。\"}]," +
                        "\"revisions\":[{\"id\":\"s1\",\"after\":\"简短译文。\"}]}" ,
                List.of(), "stop", Map.of()));
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());
        Session session = session();
        session.upsertSegment(new Session.Segment("s1", "API is stable and complete",
                "这是一个完整的实时译文，包含编号一二三和报告排序。", 0, 1000, "final"));

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("partial", result.status());
        assertTrue(result.finalById().isEmpty());
        assertTrue(result.error().contains("完整性保护"));
        assertTrue(result.revisions().isEmpty());
        service.shutdown();
    }

    @Test
    void rejectsSameLengthCorrectionThatDropsLiveContent() {
        DashScopeClient client = mock(DashScopeClient.class);
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-3", "qwen-plus", "{\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"第一轮检查完成。\"}]}",
                List.of(), "stop", Map.of()));
        FinalCorrectionService service = new FinalCorrectionService(client, configured(), new ObjectMapper());
        Session session = session();
        session.upsertSegment(new Session.Segment("s1", "First round checks low latency.",
                "第一轮检查延迟低。", 0, 1000, "final"));

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("partial", result.status());
        assertTrue(result.finalById().isEmpty());
        assertTrue(result.error().contains("完整性保护"));
        service.shutdown();
    }

    @Test
    void batchesLongCorrectionAndMergesEachWindow() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        properties.setFinalCorrectionBatchSize(2);
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-batch", "qwen-plus", "{"+
                        "\"summary\":\"批次完成\",\"segments\":["+
                        "{\"id\":\"s1\",\"finalTranslation\":\"最终一\"},"+
                        "{\"id\":\"s2\",\"finalTranslation\":\"最终二\"},"+
                        "{\"id\":\"s3\",\"finalTranslation\":\"最终三\"},"+
                        "{\"id\":\"s4\",\"finalTranslation\":\"最终四\"},"+
                        "{\"id\":\"s5\",\"finalTranslation\":\"最终五\"}]}" ,
                List.of(), "stop", Map.of()));
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());
        Session session = Session.create("long-correction", "long", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        for (int index = 1; index <= 5; index++) {
            session.addSegment(new Session.Segment("s" + index, "source " + index,
                    "实时 " + index, index * 1000L, index * 1000L + 500L, "final"));
        }

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("completed", result.status());
        assertEquals(5, result.finalById().size());
        assertEquals("最终三", result.finalById().get("s3"));
        verify(client, times(3)).generate(any(), any(), any(), any());
        service.shutdown();
    }

    private static DashScopeProperties configured() {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        return properties;
    }

    private static Session session() {
        Session session = Session.create("correction", "correction", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false,
                List.of(new Session.GlossaryTerm("API", "接口", 5, null)));
        session.addSegment(new Session.Segment("s1", "API is stable", "API 很稳定", 0, 1000, "final"));
        session.addSegment(new Session.Segment("s2", "It works", "它能工作", 1000, 2000, "final"));
        return session;
    }
}
