package com.babelflux.backend.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "babelflux.dashscope")
public class DashScopeProperties {
    private String apiKey;
    private String workspaceId;
    private String baseUrl;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration speechHandshakeTimeout = Duration.ofSeconds(5);
    private List<String> allowedTtsModels = List.of("qwen3-tts-flash-realtime");
    private String liveTranslateModel = "qwen3.5-livetranslate-flash-realtime";
    private String liveTranslateAsrModel = "qwen3-asr-flash-realtime";
    private String realtimeRevisionModel = "qwen-flash";
    private String finalCorrectionModel = "qwen-plus";
    private String fastRealtimeRevisionModel = "qwen-flash";
    private String fastFinalCorrectionModel = "qwen-flash";
    private String accurateRealtimeRevisionModel = "qwen-plus";
    private String accurateFinalCorrectionModel = "qwen-plus";
    private String costRealtimeRevisionModel = "qwen-flash";
    private String costFinalCorrectionModel = "qwen-flash";
    private Duration finalCorrectionTimeout = Duration.ofSeconds(30);
    private int realtimeRevisionWindowSegments = 4;
    private int realtimeRevisionMaxPerMinute = 6;
    private int realtimeQueueFrames = 250;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getSpeechHandshakeTimeout() { return speechHandshakeTimeout; }
    public void setSpeechHandshakeTimeout(Duration speechHandshakeTimeout) { this.speechHandshakeTimeout = speechHandshakeTimeout; }
    public List<String> getAllowedTtsModels() { return allowedTtsModels; }
    public void setAllowedTtsModels(List<String> allowedTtsModels) {
        this.allowedTtsModels = allowedTtsModels == null ? List.of() : List.copyOf(allowedTtsModels);
    }
    public String getLiveTranslateModel() { return liveTranslateModel; }
    public void setLiveTranslateModel(String liveTranslateModel) { this.liveTranslateModel = liveTranslateModel; }
    public String getLiveTranslateAsrModel() { return liveTranslateAsrModel; }
    public void setLiveTranslateAsrModel(String liveTranslateAsrModel) { this.liveTranslateAsrModel = liveTranslateAsrModel; }
    public String getRealtimeRevisionModel() { return realtimeRevisionModel; }
    public void setRealtimeRevisionModel(String realtimeRevisionModel) { this.realtimeRevisionModel = realtimeRevisionModel; }
    public String getFinalCorrectionModel() { return finalCorrectionModel; }
    public void setFinalCorrectionModel(String finalCorrectionModel) { this.finalCorrectionModel = finalCorrectionModel; }
    public String getFastRealtimeRevisionModel() { return fastRealtimeRevisionModel; }
    public void setFastRealtimeRevisionModel(String value) { this.fastRealtimeRevisionModel = value; }
    public String getFastFinalCorrectionModel() { return fastFinalCorrectionModel; }
    public void setFastFinalCorrectionModel(String value) { this.fastFinalCorrectionModel = value; }
    public String getAccurateRealtimeRevisionModel() { return accurateRealtimeRevisionModel; }
    public void setAccurateRealtimeRevisionModel(String value) { this.accurateRealtimeRevisionModel = value; }
    public String getAccurateFinalCorrectionModel() { return accurateFinalCorrectionModel; }
    public void setAccurateFinalCorrectionModel(String value) { this.accurateFinalCorrectionModel = value; }
    public String getCostRealtimeRevisionModel() { return costRealtimeRevisionModel; }
    public void setCostRealtimeRevisionModel(String value) { this.costRealtimeRevisionModel = value; }
    public String getCostFinalCorrectionModel() { return costFinalCorrectionModel; }
    public void setCostFinalCorrectionModel(String value) { this.costFinalCorrectionModel = value; }
    public Duration getFinalCorrectionTimeout() { return finalCorrectionTimeout; }
    public void setFinalCorrectionTimeout(Duration value) { this.finalCorrectionTimeout = value; }
    public int getRealtimeRevisionWindowSegments() { return realtimeRevisionWindowSegments; }
    public void setRealtimeRevisionWindowSegments(int value) { this.realtimeRevisionWindowSegments = value; }
    public int getRealtimeRevisionMaxPerMinute() { return realtimeRevisionMaxPerMinute; }
    public void setRealtimeRevisionMaxPerMinute(int value) { this.realtimeRevisionMaxPerMinute = value; }
    public int getRealtimeQueueFrames() { return realtimeQueueFrames; }
    public void setRealtimeQueueFrames(int value) {
        if (value < 25 || value > 10_000) {
            throw new IllegalArgumentException("Realtime queue frames must be between 25 and 10000");
        }
        this.realtimeQueueFrames = value;
    }

    public boolean isOpenAiCompatible() {
        return baseUrl != null && baseUrl.contains("/compatible-mode/");
    }
}
