package com.hackathon.sessionreplay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path logsDir;

    public DashboardController() {
        Path projectRoot = Paths.get(System.getProperty("user.dir")).getParent();
        this.logsDir = projectRoot.resolve("logs");
    }

    @GetMapping("/session/{fileName}")
    public ResponseEntity<?> getSession(@PathVariable String fileName) {
        try {
            File file = logsDir.resolve(fileName).toFile();
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            String content = Files.readString(file.toPath());
            JsonNode json = objectMapper.readTree(content);
            return ResponseEntity.ok(json);
        } catch (Exception e) {
            log.error("Error reading session {}: {}", fileName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummary>> getSessions() {
        try {
            List<SessionSummary> sessions = new ArrayList<>();
            File dir = logsDir.toFile();

            if (!dir.exists() || !dir.isDirectory()) {
                log.warn("Logs directory not found: {}", logsDir);
                return ResponseEntity.ok(sessions);
            }

            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) {
                return ResponseEntity.ok(sessions);
            }

            for (File file : files) {
                try {
                    SessionSummary summary = parseSessionFile(file);
                    if (summary != null) {
                        sessions.add(summary);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse {}: {}", file.getName(), e.getMessage());
                }
            }

            sessions.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));

            return ResponseEntity.ok(sessions);
        } catch (Exception e) {
            log.error("Error reading sessions: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private SessionSummary parseSessionFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            JsonNode root = objectMapper.readTree(content);

            String fileName = file.getName();
            long timestamp = extractTimestamp(fileName);
            String type = extractType(fileName);

            JsonNode urlNode = root.get("url");
            String url = urlNode != null ? urlNode.asText() : "";

            JsonNode healthScoreNode = root.get("healthScore");
            int healthScore = healthScoreNode != null ? healthScoreNode.asInt() : -1;

            JsonNode networkHealthScoreNode = root.get("networkHealthScore");
            int networkHealthScore = networkHealthScoreNode != null ? networkHealthScoreNode.asInt() : -1;

            if (healthScore == -1 && networkHealthScore != -1) {
                healthScore = networkHealthScore;
            }

            int issueCount = 0;
            String topIssue = "";
            JsonNode issuesNode = root.get("issues");
            if (issuesNode != null && issuesNode.isArray()) {
                issueCount = issuesNode.size();
                if (issueCount > 0) {
                    JsonNode firstIssue = issuesNode.get(0);
                    JsonNode titleNode = firstIssue.get("title");
                    topIssue = titleNode != null ? titleNode.asText() : "";
                }
            }

            JsonNode networkReportNode = root.get("networkReport");
            if (networkReportNode != null) {
                JsonNode redundantNode = networkReportNode.get("redundantCalls");
                if (redundantNode != null && redundantNode.isArray() && redundantNode.size() > 0) {
                    issueCount += redundantNode.size();
                    if (topIssue.isEmpty()) {
                        topIssue = redundantNode.size() + " redundant call groups";
                    }
                }
            }

            String recordingFile = findRecordingFile(timestamp);

            return new SessionSummary(
                fileName,
                timestamp,
                type,
                url,
                healthScore,
                issueCount,
                topIssue,
                recordingFile != null,
                recordingFile
            );
        } catch (Exception e) {
            log.warn("Error parsing {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private long extractTimestamp(String fileName) {
        String numStr = fileName.replaceAll("\\D+", "");
        try {
            return Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractType(String fileName) {
        if (fileName.contains("analyze")) return "analyze";
        if (fileName.contains("bug-diagnosis")) return "bug-diagnosis";
        if (fileName.contains("network-bottlenecks")) return "network-bottlenecks";
        if (fileName.contains("recording")) return "recording";
        if (fileName.contains("ux-analysis")) return "ux-analysis";
        return "unknown";
    }

    private String findRecordingFile(long timestamp) {
        File dir = logsDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.contains("recording") && name.endsWith(".json"));
        if (files == null) return null;

        for (File file : files) {
            long fileTs = extractTimestamp(file.getName());
            if (Math.abs(fileTs - timestamp) < 60000) {
                return file.getName();
            }
        }
        return null;
    }

    public static class SessionSummary {
        private String fileName;
        private long timestamp;
        private String type;
        private String url;
        private int healthScore;
        private int issueCount;
        private String topIssue;
        private boolean hasRecording;
        private String recordingFile;

        public SessionSummary(String fileName, long timestamp, String type, String url,
                         int healthScore, int issueCount, String topIssue,
                         boolean hasRecording, String recordingFile) {
            this.fileName = fileName;
            this.timestamp = timestamp;
            this.type = type;
            this.url = url;
            this.healthScore = healthScore;
            this.issueCount = issueCount;
            this.topIssue = topIssue;
            this.hasRecording = hasRecording;
            this.recordingFile = recordingFile;
        }

        public String getFileName() { return fileName; }
        public long getTimestamp() { return timestamp; }
        public String getType() { return type; }
        public String getUrl() { return url; }
        public int getHealthScore() { return healthScore; }
        public int getIssueCount() { return issueCount; }
        public String getTopIssue() { return topIssue; }
        public boolean isHasRecording() { return hasRecording; }
        public String getRecordingFile() { return recordingFile; }
    }
}