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
    private volatile String sourceUrl;
    private volatile String sourcePermission;
    private volatile boolean ttsEnabled;
    private final List<GlossaryTerm> glossary = new CopyOnWriteArrayList<>();
    private volatile SessionReport report;
    /** Runtime-only input loss telemetry; included in the final report quality notes. */
    private final java.util.concurrent.atomic.AtomicLong droppedInputFrames =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong droppedInputMs =
            new java.util.concurrent.atomic.AtomicLong();
    private final List<Segment> segments = new CopyOnWriteArrayList<>();
    private final List<Revision> revisions = new CopyOnWriteArrayList<>();

    public Session(String id, String sessionName, String sourceLanguage, String targetLanguage,
                   String domain, String modelProfile, String productMode, String inputMode,
                   String sourceLabel) {
        this(id, Instant.now(), sessionName, sourceLanguage, targetLanguage, domain,
                modelProfile, productMode, inputMode, sourceLabel, null, "idle", false, List.of());
    }

    private Session(String id, Instant createdAt, String sessionName, String sourceLanguage,
                    String targetLanguage, String domain, String modelProfile, String productMode,
                    String inputMode, String sourceLabel, String sourceUrl, String sourcePermission,
                    boolean ttsEnabled, List<GlossaryTerm> glossary) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = "created";
        this.sessionName = sessionName;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.domain = domain;
        this.modelProfile = modelProfile;
        this.productMode = productMode;
        this.inputMode = inputMode;
        this.sourceLabel = sourceLabel;
        this.sourceUrl = sourceUrl;
        this.sourcePermission = sourcePermission == null ? "idle" : sourcePermission;
        this.ttsEnabled = ttsEnabled;
        if (glossary != null) this.glossary.addAll(glossary);
    }

    public static Session create(String id, String sessionName, String sourceLanguage, String targetLanguage,
                                  String domain, String modelProfile, String productMode, String inputMode,
                                  String sourceLabel, String sourceUrl, String sourcePermission,
                                  boolean ttsEnabled, List<GlossaryTerm> glossary) {
        return new Session(id, Instant.now(), sessionName, sourceLanguage, targetLanguage, domain,
                modelProfile, productMode, inputMode, sourceLabel, sourceUrl, sourcePermission,
                ttsEnabled, glossary);
    }

    public static Session restore(String id, Instant createdAt, Instant endedAt, String status,
                                  String sessionName, String sourceLanguage, String targetLanguage,
                                  String domain, String modelProfile, String productMode,
                                  String inputMode, String sourceLabel, String sourceUrl,
                                  String sourcePermission, boolean ttsEnabled, List<GlossaryTerm> glossary,
                                  List<Segment> segments, SessionReport report) {
        Session session = new Session(id, createdAt, sessionName, sourceLanguage, targetLanguage,
                domain, modelProfile, productMode, inputMode, sourceLabel, sourceUrl, sourcePermission,
                ttsEnabled, glossary);
        session.endedAt = endedAt;
        session.status = status;
        if (segments != null) session.segments.addAll(segments);
        session.report = report;
        return session;
    }

    public static Session restore(String id, Instant createdAt, Instant endedAt, String status,
                                  String sessionName, String sourceLanguage, String targetLanguage,
                                  String domain, String modelProfile, String productMode,
                                  String inputMode, String sourceLabel, List<Segment> segments,
                                  SessionReport report) {
        return restore(id, createdAt, endedAt, status, sessionName, sourceLanguage, targetLanguage,
                domain, modelProfile, productMode, inputMode, sourceLabel, null, "idle", false,
                List.of(), segments, report);
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
    public String getSourceUrl() { return sourceUrl; }
    public String getSourcePermission() { return sourcePermission; }
    public boolean isTtsEnabled() { return ttsEnabled; }
    public List<GlossaryTerm> getGlossary() { return List.copyOf(glossary); }
    public SessionReport getReport() { return report; }
    public long getDroppedInputFrames() { return droppedInputFrames.get(); }
    public long getDroppedInputMs() { return droppedInputMs.get(); }
    public List<Segment> getSegments() { return List.copyOf(segments); }
    public List<Revision> getRevisions() { return List.copyOf(revisions); }

    public synchronized void attachReport(SessionReport report) { this.report = report; }

    public synchronized void start() {
        if ("created".equals(status)) status = "running";
    }

    public synchronized void end() {
        if ("ended".equals(status)) return;
        status = "ended";
        endedAt = Instant.now();
    }

    /** Apply the small set of per-connection overrides supported by the legacy client. */
    public synchronized void applyOverrides(String sourceLanguage, String targetLanguage, String domain,
                                             String inputMode, String sourceUrl, String modelProfile) {
        if (sourceLanguage != null && !sourceLanguage.isBlank()) this.sourceLanguage = sourceLanguage;
        if (targetLanguage != null && !targetLanguage.isBlank()) this.targetLanguage = targetLanguage;
        if (domain != null && !domain.isBlank()) this.domain = domain;
        if (inputMode != null && !inputMode.isBlank()) this.inputMode = inputMode;
        if (sourceUrl != null && !sourceUrl.isBlank()) this.sourceUrl = sourceUrl;
        if (modelProfile != null && !modelProfile.isBlank()) this.modelProfile = modelProfile;
    }

    public void addSegment(Segment segment) { segments.add(segment); }
    public synchronized void upsertSegment(Segment segment) {
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).segmentId().equals(segment.segmentId())) {
                segments.set(index, segment);
                return;
            }
        }
        segments.add(segment);
    }
    public void addRevision(Revision revision) { if (revision != null) revisions.add(revision); }

    public void recordDroppedInput(long durationMs) {
        droppedInputFrames.incrementAndGet();
        droppedInputMs.addAndGet(Math.max(0L, durationMs));
    }

    public record GlossaryTerm(String sourceTerm, String targetTerm, int priority, String note) {}

    public record Segment(String segmentId, String sourceText, String translationText,
                          long startMs, long endMs, String status) {}

    public record Revision(String segmentId, String beforeText, String afterText,
                           String reason, double confidence) {}
}
