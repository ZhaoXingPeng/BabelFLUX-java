package com.babelflux.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Micrometer-backed implementation. Metric labels deliberately exclude user and request data. */
@Component
public class MicrometerOperationalMetrics implements OperationalMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeWebSockets = new AtomicInteger();

    public MicrometerOperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("babelflux.websocket.active", activeWebSockets);
    }

    @Override
    public void httpRequest(String method, String route, int status, Duration duration) {
        Timer.builder("babelflux.http.server.requests")
                .description("Completed HTTP requests observed by BabelFlux")
                .tags("method", method, "route", route, "status", Integer.toString(status),
                        "outcome", outcome(status))
                .register(registry)
                .record(duration);
    }

    @Override
    public void apiFailure(String category, int status) {
        increment("babelflux.api.failures", "category", category, "status", Integer.toString(status));
    }

    @Override
    public void sessionLifecycle(String state) {
        increment("babelflux.session.lifecycle", "state", state);
    }

    @Override
    public void webSocketOpened() {
        activeWebSockets.incrementAndGet();
        increment("babelflux.websocket.connections", "state", "opened");
    }

    @Override
    public void webSocketRejected(String reason) {
        increment("babelflux.websocket.connections", "state", "rejected", "reason", reason);
    }

    @Override
    public void webSocketClosed(String outcome) {
        activeWebSockets.updateAndGet(current -> Math.max(0, current - 1));
        increment("babelflux.websocket.connections", "state", "closed", "outcome", outcome);
    }

    @Override
    public void asyncSucceeded(String component, String operation) {
        increment("babelflux.async.tasks", "component", component, "operation", operation, "outcome", "success");
    }

    @Override
    public void asyncRetry(String component, String operation) {
        increment("babelflux.async.tasks", "component", component, "operation", operation, "outcome", "retry");
    }

    @Override
    public void dependencyFailure(String dependency) {
        increment("babelflux.dependency.failures", "dependency", dependency);
    }

    private void increment(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private static String outcome(int status) {
        if (status >= 500) return "server_error";
        if (status >= 400) return "client_error";
        if (status >= 300) return "redirection";
        return "success";
    }
}
