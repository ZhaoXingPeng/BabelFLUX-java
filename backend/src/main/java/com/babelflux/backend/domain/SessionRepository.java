package com.babelflux.backend.domain;

import java.util.List;
import java.util.Optional;

public interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(String id);
    /**
     * Loads one aggregate while reserving its finalization row for the current transaction.
     * In-memory implementations have no external transaction, so the default is a normal read.
     */
    default Optional<Session> findByIdForUpdate(String id) { return findById(id); }
    List<Session> findAll();
    boolean deleteById(String id);
}
