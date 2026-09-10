package com.babelflux.backend.observability;

import java.time.Duration;

/**
 * Records operational signals with fixed, low-cardinality labels only.
 * Identifiers, credentials, audio and user-generated text must never be labels.
 */
public interface OperationalMetrics {
    OperationalMetrics NOOP = new OperationalMetrics() { };

    default void httpRequest(String method, String route, int status, Duration duration) { }
    default void apiFailure(String category, int status) { }
    default void sessionLifecycle(String state) { }
    default void webSocketOpened() { }
    default void webSocketRejected(String reason) { }
    default void webSocketClosed(String outcome) { }
    default void asyncSucceeded(String component, String operation) { }
    default void asyncRetry(String component, String operation) { }
    default void dependencyFailure(String dependency) { }
}
