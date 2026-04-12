package com.hackathon.sessionreplay.api;

import com.hackathon.sessionreplay.service.AIService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RecordingController {

    private final AIService aiService;

    public RecordingController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> analyzeRecording(@RequestBody Map<String, Object> recording) {
        String analysis = aiService.analyzeRecording(recording);
        return ResponseEntity.ok(Map.of("analysis", analysis));
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, String>> generateReport(@RequestBody Map<String, Object> recording) {
        String report = aiService.generateReport(recording);
        return ResponseEntity.ok(Map.of("report", report));
    }

    @PostMapping("/root-cause")
    public ResponseEntity<Map<String, String>> analyzeRootCause(@RequestBody Map<String, Object> recording) {
        String rootCause = aiService.analyzeRootCause(recording);
        return ResponseEntity.ok(Map.of("rootCause", rootCause));
    }

    @PostMapping("/reproduce")
    public ResponseEntity<Map<String, String>> generateReproductionSteps(@RequestBody Map<String, Object> recording) {
        String steps = aiService.generateReproductionSteps(recording);
        return ResponseEntity.ok(Map.of("steps", steps));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
