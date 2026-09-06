package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeRevisionServiceTest {
    @Test
    void onlyAcceptsHighConfidenceChangesBeforeLatestSegment() {
        DashScopeClient client = mock(DashScopeClient.class);
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        when(client.generate(any(), any(), any(), any())).thenReturn(new DashScopeClient.LlmGenerateResponse(
                "req-1", "qwen-flash", "{\"revisions\":["
                        + "{\"segmentId\":\"s1\",\"afterText\":\"修正一\",\"reason\":\"术语\",\"confidence\":0.9},"
                        + "{\"segmentId\":\"s2\",\"afterText\":\"不应修改\",\"confidence\":0.95},"
                        + "{\"segmentId\":\"s1\",\"afterText\":\"低置信\",\"confidence\":0.2}]}"
                , List.of(), "stop", Map.of()));
        RealtimeRevisionService service = new RealtimeRevisionService(client, properties, new ObjectMapper());
        Session session = Session.create("revision", "revision", "en", "zh", "技术", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        List<Session.Segment> segments = List.of(
                new Session.Segment("s1", "API", "原译文一", 0, 1000, "final"),
                new Session.Segment("s2", "works", "原译文二", 1000, 2000, "final"));

        List<RealtimeRevisionService.Revision> revisions = service.review(session, segments);

        assertEquals(1, revisions.size());
        assertEquals("s1", revisions.getFirst().segmentId());
        assertEquals("修正一", revisions.getFirst().afterText());
    }
}
