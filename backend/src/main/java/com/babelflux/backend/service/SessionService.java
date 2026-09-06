package com.babelflux.backend.service;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionRepository;
import com.babelflux.backend.web.dto.CreateSessionRequest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final SessionRepository repository;

    public SessionService(SessionRepository repository) { this.repository = repository; }

    public Session create(CreateSessionRequest request) {
        var session = new Session(
                UUID.randomUUID().toString(),
                defaultValue(request.sessionName(), "未命名同传"),
                defaultValue(request.sourceLanguage(), "auto"),
                defaultValue(request.targetLanguage(), "zh"),
                defaultValue(request.domain(), "通用"),
                defaultValue(request.modelProfile(), "智能默认"),
                defaultValue(request.productMode(), "quick"),
                defaultValue(request.inputMode(), "demo"),
                defaultValue(request.sourceFileName(), defaultValue(request.sourceKey(), "demo")));
        return repository.save(session);
    }

    public Session get(String id) { return repository.findById(id).orElseThrow(() -> new SessionNotFoundException(id)); }
    public List<Session> list() { return repository.findAll().stream().sorted(Comparator.comparing(Session::getCreatedAt).reversed()).toList(); }
    public void delete(String id) { if (!repository.deleteById(id)) throw new SessionNotFoundException(id); }

    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String id) { super("session not found: " + id); }
    }
}
