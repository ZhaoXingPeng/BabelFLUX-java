package com.babelflux.backend.websocket;

import com.babelflux.backend.config.BabelFluxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Event fan-out used by desktop handoff connections. Local subscribers receive a
 * bounded replay window; when Redis is enabled, Pub/Sub forwards live events to
 * subscribers attached to another application instance.
 */
@Component
public class SessionEventHub {
    private static final int HISTORY_LIMIT = 200;
    private static final int SUBSCRIBER_QUEUE_LIMIT = 100;
    private static final long IDLE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final String REDIS_EVENT_PREFIX = "babelflux:events:session:";
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer redisListener;
    private final String nodeId = UUID.randomUUID().toString();

    public SessionEventHub() { this(null, null, null, null); }

    SessionEventHub(StringRedisTemplate redis, ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.redis = redis;
        this.redisListener = null;
    }

    @Autowired
    public SessionEventHub(BabelFluxProperties properties,
                           ObjectProvider<StringRedisTemplate> redisProvider,
                           ObjectProvider<RedisConnectionFactory> factoryProvider,
                           ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        StringRedisTemplate redisTemplate = null;
        RedisMessageListenerContainer listener = null;
        if (properties != null && properties.getInfrastructure().isRedisEnabled()) {
            redisTemplate = redisProvider == null ? null : redisProvider.getIfAvailable();
            RedisConnectionFactory factory = factoryProvider == null ? null : factoryProvider.getIfAvailable();
            if (redisTemplate != null && factory != null) {
                listener = new RedisMessageListenerContainer();
                listener.setConnectionFactory(factory);
                listener.addMessageListener(this::receiveRemote, new PatternTopic(REDIS_EVENT_PREFIX + "*"));
                try {
                    listener.afterPropertiesSet();
                    listener.start();
                } catch (Exception error) {
                    throw new IllegalStateException("Redis Pub/Sub listener failed to start", error);
                }
            }
        }
        this.redis = redisTemplate;
        this.redisListener = listener;
    }

    public void publish(String sessionId, Map<String, Object> event) {
        if (sessionId == null || sessionId.isBlank() || event == null) return;
        Map<String, Object> immutable = Map.copyOf(event);
        if (!publishLocal(sessionId, immutable)) return;
        publishRemote(sessionId, immutable);
    }

    private boolean publishLocal(String sessionId, Map<String, Object> event) {
        Channel channel = channels.computeIfAbsent(sessionId, ignored -> new Channel());
        synchronized (channel) {
            if (channel.completed) return false;
            channel.lastActivityNanos = System.nanoTime();
            channel.history.addLast(event);
            while (channel.history.size() > HISTORY_LIMIT) channel.history.removeFirst();
            for (BlockingQueue<Map<String, Object>> queue : channel.subscribers) offerLatest(queue, event);
        }
        return true;
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

    private void publishRemote(String sessionId, Map<String, Object> event) {
        if (redis == null) return;
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "publisher", nodeId,
                    "sessionId", sessionId,
                    "event", event));
            redis.convertAndSend(REDIS_EVENT_PREFIX + sessionId, payload);
        } catch (Exception ignored) {
            // Local primary delivery remains available; Pub/Sub is live-only and
            // the durable report path is persisted through the MySQL outbox.
        }
    }

    void receiveRemote(org.springframework.data.redis.connection.Message message, byte[] pattern) {
        try {
            acceptRemote(message);
        } catch (Exception ignored) {
            // A malformed/expired live event must not terminate the listener.
        }
    }

    boolean acceptRemote(org.springframework.data.redis.connection.Message message) throws Exception {
        JsonNode root = mapper.readTree(message.getBody());
        if (nodeId.equals(root.path("publisher").asText())) return false;
        String sessionId = root.path("sessionId").asText();
        String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
        if (sessionId.isBlank() || !channel.equals(REDIS_EVENT_PREFIX + sessionId)) return false;
        Map<String, Object> event = mapper.convertValue(root.path("event"), new TypeReference<>() {});
        boolean accepted = publishLocal(sessionId, Map.copyOf(event));
        if (accepted && "session_report".equals(event.get("type"))) complete(sessionId);
        return accepted;
    }

    @PreDestroy
    void shutdown() {
        if (redisListener != null) redisListener.stop();
    }

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
