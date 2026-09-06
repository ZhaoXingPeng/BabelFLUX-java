package com.babelflux.backend.provider.dashscope;

import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/models")
public class DashScopeSpeechController {
    private final DashScopeSpeechClient speech;

    public DashScopeSpeechController(DashScopeSpeechClient speech) { this.speech = speech; }

    @PostMapping(value = "/asr/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AsrResponse transcribe(@RequestPart("audio") MultipartFile audio,
                                  @RequestParam(value = "model", defaultValue = "fun-asr-realtime") String model,
                                  @RequestParam(value = "audioFormat", defaultValue = "pcm") String audioFormat,
                                  @RequestParam(value = "sampleRate", defaultValue = "16000") int sampleRate) throws Exception {
        DashScopeSpeechClient.AsrResult result = speech.transcribe(audio.getBytes(), model, audioFormat, sampleRate);
        List<AsrSegmentResponse> segments = result.segments().stream()
                .map(segment -> new AsrSegmentResponse(segment.text(), segment.startMs(), segment.endMs(), segment.isFinal()))
                .toList();
        return new AsrResponse(result.requestId(), result.model(), result.text(), segments, result.events(), result.usage());
    }

    @PostMapping("/tts/speech")
    public TtsResponse synthesize(@RequestBody TtsRequest request) {
        TtsRequest normalized = request == null ? new TtsRequest(null, null, null, null, null, null, null) : request;
        DashScopeSpeechClient.TtsResult result = speech.synthesize(normalized.text(), normalized.model(),
                normalized.voice(), normalized.languageType(), normalized.audioFormat(), normalized.sampleRate(), normalized.mode());
        byte[] audio = result.audio();
        return new TtsResponse(result.model(), result.voice(), Base64.getEncoder().encodeToString(audio), audio.length,
                result.audioFormat(), result.sampleRate(), result.sessionId(), result.events());
    }

    public record AsrResponse(String requestId, String model, String text, List<AsrSegmentResponse> segments,
                              List<String> events, java.util.Map<String, Object> usage) {}
    public record AsrSegmentResponse(String text, Long startMs, Long endMs, boolean isFinal) {}
    public record TtsRequest(String text, String model, String voice, String languageType,
                             String audioFormat, Integer sampleRate, String mode) {
        public TtsRequest {
            model = model == null || model.isBlank() ? "qwen3-tts-flash-realtime" : model;
            voice = voice == null || voice.isBlank() ? "Cherry" : voice;
            languageType = languageType == null || languageType.isBlank() ? "Auto" : languageType;
            audioFormat = audioFormat == null || audioFormat.isBlank() ? "pcm" : audioFormat;
            sampleRate = sampleRate == null ? 24_000 : sampleRate;
            mode = mode == null || mode.isBlank() ? "commit" : mode;
        }
    }
    public record TtsResponse(String model, String voice, String audioBase64, int audioBytes,
                              String audioFormat, int sampleRate, String sessionId, List<String> events) {}
}
