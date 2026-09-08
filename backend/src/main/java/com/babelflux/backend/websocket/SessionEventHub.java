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
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final long IDLE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void publish(String sessionId, Map<String, Object> event) {
        if (sessionId == null || sessionId.isBlank() || event == null) return;
        Channel channel = channels.computeIfAbsent(sessionId, ignored -> new Channel());
        synchronized (channel) {
            if (channel.completed) return;
            channel.lastActivityNanos = System.nanoTime();
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
            channel.lastActivityNanos = System.nanoTime();
            replay = List.copyOf(channel.history);
            channel.subscribers.add(queue);
        }
        return new Subscription(sessionId, channel, queue, replay);
    }

    public void unsubscribe(Subscription subscription) {
        if (subscription == null) return;
        synchronized (subscription.channel()) {
            subscription.channel().subscribers.remove(subscription.queue());
            if (subscription.channel().subscribers.isEmpty() && subscription.channel().completed) {
                channels.remove(subscription.sessionId(), subscription.channel());
            }
        }
    }

    /** Marks a session terminal so its replay history can be reclaimed after handoff sockets leave. */
    public void complete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        Channel channel = channels.get(sessionId);
        if (channel == null) return;
        synchronized (channel) {
            channel.completed = true;
            channel.lastActivityNanos = System.nanoTime();
            if (channel.subscribers.isEmpty()) channels.remove(sessionId, channel);
        }
    }

    @Scheduled(fixedDelayString = "${babelflux.websocket.event-hub-cleanup-ms:60000}")
    void cleanupExpiredChannels() { cleanupExpired(System.nanoTime()); }

    void cleanupExpired(long nowNanos) {
        long cutoff = nowNanos - IDLE_TTL_NANOS;
        channels.forEach((sessionId, channel) -> {
            synchronized (channel) {
                if (channel.subscribers.isEmpty() && channel.lastActivityNanos < cutoff) {
                    channels.remove(sessionId, channel);
                }
            }
        });
    }

    int channelCount() { return channels.size(); }

    private static void offerLatest(BlockingQueue<Map<String, Object>> queue, Map<String, Object> event) {
        if (queue.offer(event)) return;
        queue.poll();
        queue.offer(event);
    }

    static final class Channel {
        private final Deque<Map<String, Object>> history = new ArrayDeque<>();
        private final List<BlockingQueue<Map<String, Object>>> subscribers = new ArrayList<>();
        private long lastActivityNanos = System.nanoTime();
        private boolean completed;
    }

    public record Subscription(String sessionId, Channel channel,
                               BlockingQueue<Map<String, Object>> queue,
                               List<Map<String, Object>> replay) {
        public Map<String, Object> await(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }
}
