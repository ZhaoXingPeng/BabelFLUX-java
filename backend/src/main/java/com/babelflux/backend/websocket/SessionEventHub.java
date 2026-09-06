package com.babelflux.backend.websocket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Process-local event fan-out used by desktop handoff connections. RabbitMQ remains
 * the durable integration bus; this hub only keeps a bounded replay window for
 * sockets attached to the same application instance.
 */
@Component
public class SessionEventHub {
    private static final int HISTORY_LIMIT = 200;
    private static final int SUBSCRIBER_QUEUE_LIMIT = 100;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void publish(String sessionId, Map<String, Object> event) {
        if (sessionId == null || sessionId.isBlank() || event == null) return;
        Channel channel = channels.computeIfAbsent(sessionId, ignored -> new Channel());
        synchronized (channel) {
            channel.history.addLast(Map.copyOf(event));
            while (channel.history.size() > HISTORY_LIMIT) channel.history.removeFirst();
            for (BlockingQueue<Map<String, Object>> queue : channel.subscribers) offerLatest(queue, Map.copyOf(event));
        }
    }

    public Subscription subscribe(String sessionId) {
        Channel channel = channels.computeIfAbsent(sessionId, ignored -> new Channel());
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(SUBSCRIBER_QUEUE_LIMIT);
        List<Map<String, Object>> replay;
        synchronized (channel) {
            replay = List.copyOf(channel.history);
            channel.subscribers.add(queue);
        }
        return new Subscription(sessionId, channel, queue, replay);
    }

    public void unsubscribe(Subscription subscription) {
        if (subscription == null) return;
        synchronized (subscription.channel()) {
            subscription.channel().subscribers.remove(subscription.queue());
            if (subscription.channel().subscribers.isEmpty() && subscription.channel().history.isEmpty()) {
                channels.remove(subscription.sessionId(), subscription.channel());
            }
        }
    }

    private static void offerLatest(BlockingQueue<Map<String, Object>> queue, Map<String, Object> event) {
        if (queue.offer(event)) return;
        queue.poll();
        queue.offer(event);
    }

    static final class Channel {
        private final Deque<Map<String, Object>> history = new ArrayDeque<>();
        private final List<BlockingQueue<Map<String, Object>>> subscribers = new ArrayList<>();
    }

    public record Subscription(String sessionId, Channel channel,
                               BlockingQueue<Map<String, Object>> queue,
                               List<Map<String, Object>> replay) {
        public Map<String, Object> await(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }
}
