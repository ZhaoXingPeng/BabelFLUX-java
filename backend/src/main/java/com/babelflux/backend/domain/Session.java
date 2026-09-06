package com.babelflux.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Session {
    private final String id;
    private final Instant createdAt;
    private volatile Instant endedAt;
    private volatile String status;
    private volatile String sessionName;
    private volatile String sourceLanguage;
    private volatile String targetLanguage;
    private volatile String domain;
    private volatile String modelProfile;
    private volatile String productMode;
    private volatile String inputMode;
    private volatile String sourceLabel;
    private volatile SessionReport report;
    private final List<Segment> segments = new CopyOnWriteArrayList<>();

    public Session(String id, String sessionName, String sourceLanguage, String targetLanguage,
                   String domain, String modelProfile, String productMode, String inputMode,
                   String sourceLabel) {
        this.id = id;
        this.createdAt = Instant.now();
        this.status = "created";
        this.sessionName = sessionName;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.domain = domain;
        this.modelProfile = modelProfile;
        this.productMode = productMode;
        this.inputMode = inputMode;
        this.sourceLabel = sourceLabel;
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getStatus() { return status; }
    public String getSessionName() { return sessionName; }
    public String getSourceLanguage() { return sourceLanguage; }
    public String getTargetLanguage() { return targetLanguage; }
    public String getDomain() { return domain; }
    public String getModelProfile() { return modelProfile; }
    public String getProductMode() { return productMode; }
    public String getInputMode() { return inputMode; }
    public String getSourceLabel() { return sourceLabel; }
    public SessionReport getReport() { return report; }
    public List<Segment> getSegments() { return List.copyOf(segments); }

    public synchronized void attachReport(SessionReport report) { this.report = report; }

    public synchronized void start() {
        if ("created".equals(status)) status = "running";
    }

    public synchronized void end() {
        if ("ended".equals(status)) return;
        status = "ended";
        endedAt = Instant.now();
    }
    public void addSegment(Segment segment) { segments.add(segment); }

    public record Segment(String segmentId, String sourceText, String translationText,
                          long startMs, long endMs, String status) {}
}
