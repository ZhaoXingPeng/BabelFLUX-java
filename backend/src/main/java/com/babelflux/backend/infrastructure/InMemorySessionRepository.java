package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
public class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Override public Session save(Session session) { sessions.put(session.getId(), session); return session; }
    @Override public Optional<Session> findById(String id) { return Optional.ofNullable(sessions.get(id)); }
    @Override public List<Session> findAll() { return new ArrayList<>(sessions.values()); }
    @Override public boolean deleteById(String id) { return sessions.remove(id) != null; }
}
