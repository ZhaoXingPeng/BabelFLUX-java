package com.babelflux.backend.web;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.babelflux.backend.web.dto.CreateSessionRequest;
import com.babelflux.backend.web.dto.CreateSessionResponse;
import com.babelflux.backend.web.dto.SessionHistoryEntry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService service;
    private final SessionTokenService tokens;

    public SessionController(SessionService service, SessionTokenService tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @PostMapping
    public CreateSessionResponse create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        Session session = service.create(request == null ? new CreateSessionRequest(null, null, null, null, null, null, null, null, null, null, null) : request);
        return new CreateSessionResponse(session.getId(), tokens.issue(session.getId()), session.getStatus());
    }

    @GetMapping("/history")
    public Map<String, List<SessionHistoryEntry>> history() {
        return Map.of("items", service.list().stream().map(SessionHistoryEntry::from).toList());
    }

    @GetMapping("/history/{id}")
    public SessionHistoryEntry history(@PathVariable String id) { return SessionHistoryEntry.from(service.get(id)); }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<?> report(@PathVariable String id) {
        Session session = service.get(id);
        if (session.getStatus().equals("created") || session.getStatus().equals("running")) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("reportId", session.getId(), "sessionId", session.getId(), "sessionName", session.getSessionName(), "segments", session.getSegments()));
    }
}
