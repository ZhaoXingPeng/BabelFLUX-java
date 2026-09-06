package com.babelflux.backend.messaging;

import java.time.Instant;
import java.util.Map;

/** Stable application event envelope; provider and transport details stay outside the contract. */
public record SessionEvent(String eventId, String eventType, int schemaVersion,
                           String sessionId, Instant occurredAt, Map<String, Object> data) {}
