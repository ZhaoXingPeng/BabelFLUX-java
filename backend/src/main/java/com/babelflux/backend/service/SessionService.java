package com.babelflux.backend.service;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.domain.SessionRepository;
import com.babelflux.backend.web.dto.CreateSessionRequest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final SessionRepository repository;
    private final SessionReportService reports;

    public SessionService(SessionRepository repository, SessionReportService reports) {
        this.repository = repository;
        this.reports = reports;
    }

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

    public SessionReport finish(String id) {
        Session session = get(id);
        if (session.getReport() != null) return session.getReport();
        session.end();
        SessionReport report = reports.generate(session);
        session.attachReport(report);
        repository.save(session);
        return report;
    }

    public SessionReport report(String id) {
        Session session = get(id);
        if (session.getReport() == null) throw new ReportNotReadyException(id);
        return session.getReport();
    }

    public Session get(String id) { return repository.findById(id).orElseThrow(() -> new SessionNotFoundException(id)); }
    public List<Session> list() { return repository.findAll().stream().sorted(Comparator.comparing(Session::getCreatedAt).reversed()).toList(); }
    public void delete(String id) { if (!repository.deleteById(id)) throw new SessionNotFoundException(id); }

    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String id) { super("session not found: " + id); }
    }

    public static class ReportNotReadyException extends RuntimeException {
        public ReportNotReadyException(String id) { super("report not ready: " + id); }
    }
}
