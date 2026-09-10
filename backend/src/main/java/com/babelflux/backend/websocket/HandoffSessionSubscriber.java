package com.babelflux.backend.websocket;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.WebSocketSession;

/** Owns read-only handoff subscriptions and their lifecycle. */
final class HandoffSessionSubscriber {
    private final SessionEventHub eventHub;
    private final WebSocketEventSender sender;
    private final ConcurrentMap<String, SessionEventHub.Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    HandoffSessionSubscriber(SessionEventHub eventHub, WebSocketEventSender sender) {
        this.eventHub = eventHub;
        this.sender = sender;
    }

    boolean isHandoff(String socketId) { return subscriptions.containsKey(socketId); }

    void start(WebSocketSession socket, String sessionId) throws IOException {
        SessionEventHub.Subscription subscription = eventHub.subscribe(sessionId);
        subscriptions.put(socket.getId(), subscription);
        try {
            sender.send(socket, Map.of("type", "session_started", "sessionId", sessionId));
            for (Map<String, Object> event : subscription.replay()) sender.send(socket, event);
            tasks.put(socket.getId(), executor.submit(() -> forward(socket, subscription)));
        } catch (IOException error) {
            close(socket.getId());
            throw error;
        }
    }

    boolean close(String socketId) {
        SessionEventHub.Subscription subscription = subscriptions.remove(socketId);
        if (subscription == null) return false;
        eventHub.unsubscribe(subscription);
        Future<?> task = tasks.remove(socketId);
        if (task != null) task.cancel(true);
        return true;
    }

    @PreDestroy
    void shutdown() {
        tasks.values().forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }

    private void forward(WebSocketSession socket, SessionEventHub.Subscription subscription) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Map<String, Object> event = subscription.await(1, TimeUnit.SECONDS);
                if (event != null) sender.send(socket, event);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // A display-only socket can close while its primary session continues.
        }
    }
}
