package com.babelflux.backend.service;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.domain.SessionRepository;
import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEvent;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.search.ReportIndexingPort;
import com.babelflux.backend.web.dto.CreateSessionRequest;
import java.util.Comparator;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private static final Set<String> INPUT_MODES = Set.of("demo", "url", "microphone", "browser_audio",
            "screen_window", "media_element_audio", "system_audio");
    private final SessionRepository repository;
    private final SessionReportService reports;
    private final BabelFluxProperties properties;
    private final JdbcSessionEventOutbox outbox;
    private final SessionEventFactory eventFactory;
    private final ReportIndexingPort reportIndexing;
    /** Deduplicates concurrent finish calls from stop/disconnect races in this JVM. */
    private final ConcurrentMap<String, CompletableFuture<SessionReport>> finishing = new ConcurrentHashMap<>();

    public SessionService(SessionRepository repository, SessionReportService reports,
                          BabelFluxProperties properties, JdbcSessionEventOutbox outbox,
                          SessionEventFactory eventFactory, ReportIndexingPort reportIndexing) {
        this.repository = repository;
        this.reports = reports;
        this.properties = properties;
        this.outbox = outbox;
        this.eventFactory = eventFactory;
        this.reportIndexing = reportIndexing;
    }

    @Transactional
    public Session create(CreateSessionRequest request) {
        if (request == null) throw new InvalidSessionRequestException("request is required");
        if (!isSupportedInputMode(request.inputMode())) {
            throw new InvalidSessionRequestException("unsupported input mode: " + request.inputMode());
        }
        var glossary = request.glossary().stream()
                .filter(term -> term.sourceTerm() != null && !term.sourceTerm().isBlank()
                        && term.targetTerm() != null && !term.targetTerm().isBlank())
                .map(term -> new Session.GlossaryTerm(term.sourceTerm(), term.targetTerm(),
                        term.priority(), term.note()))
                .toList();
        var session = Session.create(
                UUID.randomUUID().toString(),
                defaultValue(request.sessionName(), "未命名同传"),
                defaultValue(request.sourceLanguage(), "auto"),
                defaultValue(request.targetLanguage(), "zh"),
                defaultValue(request.domain(), "通用"),
                defaultValue(request.modelProfile(), "智能默认"),
                defaultValue(request.productMode(), "quick"),
                defaultValue(request.inputMode(), "demo"),
                defaultValue(request.sourceFileName(), defaultValue(request.sourceKey(), "demo")),
                request.sourceUrl(), defaultValue(request.sourcePermission(), "idle"),
                Boolean.TRUE.equals(request.ttsEnabled()), glossary);
        Session saved = repository.save(session);
        appendEventIfEnabled(eventFactory.created(saved));
        return saved;
    }

    @Transactional
    public SessionReport finish(String id) {
        Session session = get(id);
        if (session.getReport() != null) return session.getReport();
        CompletableFuture<SessionReport> created = new CompletableFuture<>();
        CompletableFuture<SessionReport> existing = finishing.putIfAbsent(id, created);
        if (existing != null) return awaitFinish(existing);
        try {
            // Re-read after winning the slot: another path may have persisted the report
            // between the initial lookup and putIfAbsent.
            session = get(id);
            if (session.getReport() != null) {
                created.complete(session.getReport());
                return session.getReport();
            }
            session.end();
            SessionReport report = reports.generate(session);
            session.attachReport(report);
            repository.save(session);
            reportIndexing.enqueue(report);
            appendEventIfEnabled(eventFactory.finished(session, report));
            appendEventIfEnabled(eventFactory.reportGenerated(session, report));
            created.complete(report);
            return report;
        } catch (RuntimeException | Error error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            finishing.remove(id, created);
        }
    }

    private SessionReport awaitFinish(CompletableFuture<SessionReport> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    public SessionReport report(String id) {
        Session session = get(id);
        if (session.getReport() == null) throw new ReportNotReadyException(id);
        return session.getReport();
    }

    public Session get(String id) { return repository.findById(id).orElseThrow(() -> new SessionNotFoundException(id)); }
    @Transactional
    public Session saveProgress(Session session) { return repository.save(session); }
    public List<Session> list() { return repository.findAll().stream().sorted(Comparator.comparing(Session::getCreatedAt).reversed()).toList(); }
    public void delete(String id) { if (!repository.deleteById(id)) throw new SessionNotFoundException(id); }

    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private void appendEventIfEnabled(SessionEvent event) {
        if (properties.getInfrastructure().isRabbitmqEnabled()) outbox.append(event);
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String id) { super("session not found: " + id); }
    }

    public static class ReportNotReadyException extends RuntimeException {
        public ReportNotReadyException(String id) { super("report not ready: " + id); }
    }

    public static boolean isSupportedInputMode(String inputMode) {
        return inputMode == null || inputMode.isBlank() || INPUT_MODES.contains(inputMode);
    }

    public static class InvalidSessionRequestException extends RuntimeException {
        public InvalidSessionRequestException(String message) { super(message); }
    }
}
