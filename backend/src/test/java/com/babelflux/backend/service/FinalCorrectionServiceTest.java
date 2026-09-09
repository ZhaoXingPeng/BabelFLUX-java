package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    void recoversMissingSegmentsWithinTheOriginalDeadline() {
        DashScopeClient client = mock(DashScopeClient.class);
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        when(client.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = invocation.getArgument(2);
            prompts.add(String.valueOf(messages.get(1).get("content")));
            String id = call == 1 ? "s1" : "s2";
            String payload = call == 1
                    ? "{\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"初次s1\"}]}"
                    : "{\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"错误覆盖s1\"},"
                            + "{\"id\":\"s2\",\"finalTranslation\":\"补救s2\"}]}";
            return new DashScopeClient.LlmGenerateResponse("req-" + id, "qwen-plus",
                    payload,
                    List.of(), "stop", Map.of());
        });
        FinalCorrectionService service = new FinalCorrectionService(client, configured(), new ObjectMapper());
        Session session = session();

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("completed", result.status());
        assertEquals(Map.of("s1", "初次s1", "s2", "补救s2"), result.finalById());
        assertTrue(result.error().isBlank());
        assertTrue(result.qualityNotes().contains("缺段补救调用 1 次"));
        assertTrue(prompts.get(1).contains("[s2]"));
        assertFalse(prompts.get(1).contains("[s1]"));
        verify(client, times(2)).generate(any(), any(), any(), any());
        service.shutdown();
    }

    @Test
    void keepsInitialCorrectionWhenMissingSegmentRecoveryTimesOut() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        properties.setFinalCorrectionTimeout(Duration.ofMillis(200));
        AtomicInteger calls = new AtomicInteger();
        when(client.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return new DashScopeClient.LlmGenerateResponse("req-s1", "qwen-plus",
                        "{\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"初次s1\"}]}",
                        List.of(), "stop", Map.of());
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new DashScopeClient.LlmGenerateResponse("req-s2", "qwen-plus",
                    "{\"segments\":[{\"id\":\"s2\",\"finalTranslation\":\"补救s2\"}]}",
                    List.of(), "stop", Map.of());
        });
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());
        Session session = session();

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("partial", result.status());
        assertEquals(Map.of("s1", "初次s1"), result.finalById());
        assertTrue(result.error().contains("缺段补救超时"));
        assertTrue(result.qualityNotes().contains("缺段补救调用 1 次"));
        verify(client, times(2)).generate(any(), any(), any(), any());
        service.shutdown();
    }

    @Test
    void keepsInitialCorrectionWhenMissingSegmentRecoveryReturnsInvalidJson() {
        DashScopeClient client = mock(DashScopeClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return new DashScopeClient.LlmGenerateResponse("req-s1", "qwen-plus",
                        "{\"segments\":[{\"id\":\"s1\",\"finalTranslation\":\"初次s1\"}]}",
                        List.of(), "stop", Map.of());
            }
            return new DashScopeClient.LlmGenerateResponse("req-s2", "qwen-plus", "not-json",
                    List.of(), "stop", Map.of());
        });
        FinalCorrectionService service = new FinalCorrectionService(client, configured(), new ObjectMapper());
        Session session = session();

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("partial", result.status());
        assertEquals(Map.of("s1", "初次s1"), result.finalById());
        assertTrue(result.error().contains("缺段补救未返回可解析 JSON"));
        verify(client, times(2)).generate(any(), any(), any(), any());
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

    @Test
    void acceptsSourceBackedEnglishTokenCorrectionWithoutAllowingDeletion() {
        DashScopeClient client = mock(DashScopeClient.class);
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-token", "qwen-plus", "{\"segments\":[{\"id\":\"s1\","
                        + "\"finalTranslation\":\"BabelFlux语音最终版回归测试\"}]}",
                List.of(), "stop", Map.of()));
        FinalCorrectionService service = new FinalCorrectionService(client, configured(), new ObjectMapper());
        Session session = Session.create("token-correction", "token", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        session.addSegment(new Session.Segment("s1", "The Babel flux voice finalization regression test.",
                "Babble Flux语音最终版回归测试", 0, 1000, "final"));

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("completed", result.status());
        assertEquals("BabelFlux语音最终版回归测试", result.finalById().get("s1"));
        service.shutdown();
    }

    @Test
    void keepsCompletedBatchesWhenAnEarlierBatchTimesOut() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = configured();
        properties.setFinalCorrectionBatchSize(1);
        properties.setFinalCorrectionTimeout(Duration.ofMillis(300));
        when(client.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = invocation.getArgument(2);
            String prompt = String.valueOf(messages.get(1).get("content"));
            if (prompt.contains("[s1]")) Thread.sleep(500);
            String id = prompt.contains("[s1]") ? "s1" : prompt.contains("[s2]") ? "s2" : "s3";
            return new DashScopeClient.LlmGenerateResponse(
                    "req-" + id, "qwen-plus",
                    "{\"segments\":[{\"id\":\"" + id + "\",\"finalTranslation\":\"最终" + id + "\"}]}",
                    List.of(), "stop", Map.of());
        });
        FinalCorrectionService service = new FinalCorrectionService(client, properties, new ObjectMapper());
        Session session = Session.create("timeout-order", "timeout-order", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        for (int index = 1; index <= 3; index++) {
            session.addSegment(new Session.Segment("s" + index, "source " + index,
                    "实时 " + index, index * 1000L, index * 1000L + 500L, "final"));
        }

        FinalCorrectionService.CorrectionResult result = service.correct(session, session.getSegments());

        assertEquals("partial", result.status());
        assertEquals(2, result.finalById().size());
        assertEquals("最终s2", result.finalById().get("s2"));
        assertEquals("最终s3", result.finalById().get("s3"));
        assertTrue(result.error().contains("超时"));
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
