package com.hackathon.sessionreplay.api;

import com.hackathon.sessionreplay.model.AnalysisModels.*;
import com.hackathon.sessionreplay.service.AIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RecordingController {

    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);
    private final AIService aiService;

    public RecordingController(AIService aiService) {
        this.aiService = aiService;
    }

    // --- New structured analysis endpoints ---

    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeRecording(@RequestBody Map<String, Object> recording) {
        try {
            SessionAnalysis analysis = aiService.analyzeSession(recording);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            return handleAiError(e, "analyze");
        }
    }

    @PostMapping("/network-bottlenecks")
    public ResponseEntity<?> analyzeNetworkBottlenecks(@RequestBody Map<String, Object> recording) {
        try {
            NetworkBottleneckReport report = aiService.analyzeNetworkBottlenecks(recording);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            return handleAiError(e, "network-bottlenecks");
        }
    }

    @PostMapping("/bug-diagnosis")
    public ResponseEntity<?> diagnoseBug(@RequestBody Map<String, Object> recording) {
        try {
            BugDiagnosis diagnosis = aiService.diagnoseBug(recording);
            return ResponseEntity.ok(diagnosis);
        } catch (Exception e) {
            return handleAiError(e, "bug-diagnosis");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    private ResponseEntity<Map<String, String>> handleAiError(Exception e, String endpoint) {
        String message = e.getMessage();
        log.error("AI error on /{}: {}", endpoint, message);

        if (message != null && message.contains("429")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Please wait 30 seconds and try again.",
                                 "endpoint", endpoint));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "AI analysis failed: " + (message != null ? message.substring(0, Math.min(message.length(), 200)) : "Unknown error"),
                             "endpoint", endpoint));
    }
}
