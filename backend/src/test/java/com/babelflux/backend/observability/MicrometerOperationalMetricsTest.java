package com.babelflux.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerOperationalMetricsTest {
    @Test
    void recordsOnlyLowCardinalityOperationalSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new MicrometerOperationalMetrics(registry);

        metrics.httpRequest("GET", "/api/sessions/{id}", 503, Duration.ofMillis(12));
        metrics.sessionLifecycle("created");
        metrics.webSocketOpened();
        metrics.webSocketRejected("invalid_token");
        metrics.webSocketClosed("normal");
        metrics.asyncRetry("outbox", "publish");
        metrics.dependencyFailure("rabbitmq");

        assertThat(registry.get("babelflux.http.server.requests")
                .tags("method", "GET", "route", "/api/sessions/{id}", "status", "503",
                        "outcome", "server_error")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("babelflux.session.lifecycle").tag("state", "created").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("babelflux.websocket.active").gauge().value()).isZero();
        assertThat(registry.get("babelflux.websocket.connections")
                .tags("state", "rejected", "reason", "invalid_token").counter().count()).isEqualTo(1);
        assertThat(registry.get("babelflux.async.tasks")
                .tags("component", "outbox", "operation", "publish", "outcome", "retry")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("babelflux.dependency.failures").tag("dependency", "rabbitmq")
                .counter().count()).isEqualTo(1);
    }
}
