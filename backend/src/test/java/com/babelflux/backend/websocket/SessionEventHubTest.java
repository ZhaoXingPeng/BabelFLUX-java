package com.babelflux.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

class SessionEventHubTest {
    @Test
    void replaysHistoryAndFansOutSubsequentEvents() throws Exception {
        SessionEventHub hub = new SessionEventHub();
        hub.publish("session-1", Map.of("type", "transcript_segment", "n", 1));
        SessionEventHub.Subscription subscription = hub.subscribe("session-1");

        assertEquals(1, subscription.replay().size());
        hub.publish("session-1", Map.of("type", "translation_segment", "n", 2));

        Map<String, Object> next = subscription.await(1, TimeUnit.SECONDS);
        assertNotNull(next);
        assertEquals("translation_segment", next.get("type"));
        hub.unsubscribe(subscription);
    }

    @Test
    void releasesCompletedChannelAfterLastSubscriberLeaves() {
        SessionEventHub hub = new SessionEventHub();
        SessionEventHub.Subscription subscription = hub.subscribe("session-1");
        hub.publish("session-1", Map.of("type", "session_report"));

        hub.complete("session-1");
        assertEquals(1, hub.channelCount());
        hub.unsubscribe(subscription);

        assertEquals(0, hub.channelCount());
    }

    @Test
    void purgesIdleChannelsWithoutSubscribers() {
        SessionEventHub hub = new SessionEventHub();
        hub.publish("session-1", Map.of("type", "transcript_segment"));
        assertEquals(1, hub.channelCount());

        hub.cleanupExpired(System.nanoTime() + TimeUnit.MINUTES.toNanos(5) + 1);

        assertEquals(0, hub.channelCount());
    }

    @Test
    void publishesAndConsumesRemoteEventWithoutRepublishingIt() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SessionEventHub hub = new SessionEventHub(redis, new ObjectMapper());
        Map<String, Object> event = Map.of("type", "translation_segment", "n", 2);

        hub.publish("session-remote", event);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("babelflux:events:session:session-remote"), payload.capture());

        String remotePayload = "{\"publisher\":\"remote-node\",\"sessionId\":\"session-remote\","
                + "\"event\":{\"type\":\"translation_segment\",\"n\":2}}";
        assertEquals(true, hub.acceptRemote(new DefaultMessage(
                "babelflux:events:session:session-remote".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                remotePayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        SessionEventHub.Subscription subscription = hub.subscribe("session-remote");
        assertEquals(2, subscription.replay().size(), "local and remote events remain in bounded history");
        assertEquals("translation_segment", subscription.replay().getLast().get("type"));
        verify(redis).convertAndSend(eq("babelflux:events:session:session-remote"), anyString());
    }
}
