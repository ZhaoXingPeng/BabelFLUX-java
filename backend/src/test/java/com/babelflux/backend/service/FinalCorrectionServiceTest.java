package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
