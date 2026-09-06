package com.babelflux.backend.domain;

import java.util.List;
import java.util.Optional;

public interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(String id);
    List<Session> findAll();
    boolean deleteById(String id);
}
