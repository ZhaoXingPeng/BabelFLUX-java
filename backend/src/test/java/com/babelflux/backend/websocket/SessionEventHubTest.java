package com.babelflux.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
}
