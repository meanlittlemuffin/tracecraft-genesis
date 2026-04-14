package com.hackathon.sessionreplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.sessionreplay.config.TracecraftProperties;
import com.hackathon.sessionreplay.model.AnalysisModels.BugDiagnosis;
import com.hackathon.sessionreplay.model.AnalysisModels.NetworkBottleneckReport;
import com.hackathon.sessionreplay.model.AnalysisModels.SessionAnalysis;
import com.hackathon.sessionreplay.service.IncidentPacketService.IncidentPacket;
import com.hackathon.sessionreplay.service.IncidentPacketService.PacketMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ServerLogService serverLogService;
    private final IncidentPacketService incidentPacketService;
    private final TracecraftProperties properties;
    private final ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastGeminiAttemptEpochMs = new AtomicLong(0L);

    public AIService(
            ChatClient.Builder builder,
            ObjectMapper objectMapper,
            ServerLogService serverLogService,
            IncidentPacketService incidentPacketService,
            TracecraftProperties properties
    ) {
        this.chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.serverLogService = serverLogService;
        this.incidentPacketService = incidentPacketService;
        this.properties = properties;
    }

    public SessionAnalysis analyzeSession(Map<String, Object> recording) {
        ServerLogService.ServerLogContext serverLogs = serverLogService.buildContext(recording);
        IncidentPacket incidentPacket = incidentPacketService.buildPacket(recording, serverLogs, PacketMode.ANALYZE);

        String raw = callModel(
                "analyze",
                incidentPacket,
                """
                        You are an expert browser session analyst. Analyze curated browser incident packets
                        to identify bugs, performance issues, UX problems, and network bottlenecks.
                        The incident packet may include compact server-log context for localhost demo apps.
                        The packet intentionally contains only the highest-signal evidence.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""",
                "Analyze this curated incident packet. Server logs are optional and should only influence your answer when they materially support the browser evidence.\n"
                        + "Respond with a JSON object containing:\n"
                        + "- \"summary\": string overview\n"
                        + "- \"healthScore\": 0-100\n"
                        + "- \"issues\": array of objects with id, category, severity, severityScore, title, description, evidence (array of eventType/timestamp/detail), impact (timeWastedMs/bandwidthWastedBytes/description)\n"
                        + "- \"networkReport\": object with summary, networkHealthScore, totalRequests, failedRequests, totalDurationMs, slowEndpoints, nplusOnePatterns, parallelizableGroups, redundantCalls, largePayloads, corsPreflights, compressionIssues, recommendations\n"
                        + "- \"uxReport\": object with summary, uxHealthScore, rageClicks, deadClicks, navigationUturns, formAbandonmentDetected, frustrationEvents, recommendations\n"
                        + "- \"recommendations\": array of priority/action/effort/codeExample\n\n"
                        + "INCIDENT_PACKET:\n"
        );
        return parseResponse(raw, SessionAnalysis.class);
    }

    public NetworkBottleneckReport analyzeNetworkBottlenecks(Map<String, Object> recording) {
        IncidentPacket incidentPacket = incidentPacketService.buildPacket(
                recording,
                ServerLogService.ServerLogContext.disabled("", "Server logs are not used for network bottleneck analysis in v1."),
                PacketMode.NETWORK_BOTTLENECKS
        );

        String raw = callModel(
                "network-bottlenecks",
                incidentPacket,
                """
                        You are a network performance expert analyzing curated browser incident packets.
                        Focus on request failures, latency, payload issues, and sequencing bottlenecks.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""",
                "Analyze the network evidence in this curated incident packet. Respond with a JSON object containing:\n"
                        + "- \"summary\": string\n"
                        + "- \"networkHealthScore\": 0-100\n"
                        + "- \"totalRequests\": number\n"
                        + "- \"failedRequests\": number\n"
                        + "- \"totalDurationMs\": number\n"
                        + "- \"slowEndpoints\": array of url/method/avgDurationMs/maxDurationMs/callCount/bottleneckPhase/severity\n"
                        + "- \"nplusOnePatterns\": array of listEndpoint/detailEndpoint/detailCallCount/totalWastedMs/suggestedFix\n"
                        + "- \"parallelizableGroups\": array of calls/currentSequentialTimeMs/estimatedParallelTimeMs/savingsMs\n"
                        + "- \"redundantCalls\": array of url/method/callCount/wastedTimeMs/suggestedFix\n"
                        + "- \"largePayloads\": array of url/sizeBytes/isCompressed/suggestedFix\n"
                        + "- \"corsPreflights\": array of targetUrl/optionsCallCount/totalOverheadMs/suggestedFix\n"
                        + "- \"compressionIssues\": array of url/uncompressedSizeBytes/contentType\n"
                        + "- \"recommendations\": array of priority/action/effort/codeExample\n\n"
                        + "INCIDENT_PACKET:\n"
        );
        return parseResponse(raw, NetworkBottleneckReport.class);
    }

    public BugDiagnosis diagnoseBug(Map<String, Object> recording) {
        ServerLogService.ServerLogContext serverLogs = serverLogService.buildContext(recording);
        IncidentPacket incidentPacket = incidentPacketService.buildPacket(recording, serverLogs, PacketMode.BUG_DIAGNOSIS);

        String raw = callModel(
                "bug-diagnosis",
                incidentPacket,
                """
                        You are a senior software engineer debugging a bug from a curated browser incident packet.
                        Browser data and server logs refer to the same localhost demo session when server logs are present.
                        Prefer conclusions supported by both browser evidence and server-log evidence.
                        If server logs are absent or inconclusive, say so instead of guessing.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""",
                "Diagnose the bug in this curated incident packet. Respond with a JSON object containing:\n\n"
                        + "1. \"rootCause\": object with:\n"
                        + "   - \"summary\": one-line root cause\n"
                        + "   - \"confidence\": string like \"85%\"\n"
                        + "   - \"triggerChain\": array of strings describing the event sequence that led to the bug\n"
                        + "   - \"evidence\": array of strings citing specific browser events and, when available, server-log evidence\n"
                        + "   - \"fix\": suggested code or configuration fix tied to the strongest evidence\n\n"
                        + "2. \"bugReport\": object with:\n"
                        + "   - \"title\": short bug title\n"
                        + "   - \"description\": detailed description of the bug\n"
                        + "   - \"severity\": CRITICAL/HIGH/MEDIUM/LOW\n"
                        + "   - \"timeline\": array of strings describing key events in chronological order, including server-log moments when relevant\n"
                        + "   - \"networkActivity\": summary of relevant network calls (failed calls, slow calls, error responses)\n"
                        + "   - \"consoleLogs\": summary of relevant console errors and warnings\n"
                        + "   - \"environment\": user agent, page URL, screen size from metadata\n\n"
                        + "3. \"reproductionSteps\": object with:\n"
                        + "   - \"steps\": array of strings (step-by-step user actions to reproduce)\n"
                        + "   - \"curlCommands\": array of strings (curl commands to reproduce the API calls that failed or triggered the bug)\n"
                        + "   - \"expectedBehavior\": what should have happened\n"
                        + "   - \"actualBehavior\": what actually happened\n\n"
                        + "4. \"serverLogSummary\": object with:\n"
                        + "   - \"used\": boolean-like value\n"
                        + "   - \"summary\": one-line explanation of whether server logs supported the RCA\n"
                        + "   - \"matchedRequest\": request path or endpoint when applicable\n"
                        + "   - \"topEvidence\": array of short strings summarizing the most relevant log lines or exceptions\n\n"
                        + "INCIDENT_PACKET:\n"
        );
        BugDiagnosis parsed = parseResponse(raw, BugDiagnosis.class);
        return ensureServerLogSummary(parsed, serverLogs);
    }

    private String callModel(String endpoint, IncidentPacket incidentPacket, String systemPrompt, String userPromptPrefix) {
        String cacheKey = endpoint + ":" + incidentPacket.hash();
        CachedResponse cached = getValidCachedResponse(cacheKey);
        if (cached != null) {
            log.info("Returning cached {} response for packet {}", endpoint, incidentPacket.hash());
            return cached.raw();
        }

        enforceCooldown(endpoint);

        String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(userPromptPrefix + incidentPacket.json())
                .call()
                .content();

        if (properties.getAi().isCacheEnabled()) {
            cache.put(cacheKey, new CachedResponse(raw, Instant.now().plusSeconds(properties.getAi().getCacheTtlSeconds())));
        }
        return raw;
    }

    private CachedResponse getValidCachedResponse(String cacheKey) {
        if (!properties.getAi().isCacheEnabled()) {
            return null;
        }

        CachedResponse cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (Instant.now().isAfter(cached.expiresAt())) {
            cache.remove(cacheKey);
            return null;
        }
        return cached;
    }

    private void enforceCooldown(String endpoint) {
        long cooldownMs = properties.getAi().getCooldownSeconds() * 1000L;
        long now = Instant.now().toEpochMilli();
        long lastAttempt = lastGeminiAttemptEpochMs.get();
        if (lastAttempt > 0 && now - lastAttempt < cooldownMs) {
            long remainingMs = cooldownMs - (now - lastAttempt);
            long remainingSeconds = Math.max(1L, (remainingMs + 999L) / 1000L);
            throw new RuntimeException("429 Gemini free-tier cooldown active for `" + endpoint + "`. Please wait " + remainingSeconds + " seconds and try again.");
        }
        lastGeminiAttemptEpochMs.set(now);
    }

    private BugDiagnosis ensureServerLogSummary(BugDiagnosis diagnosis, ServerLogService.ServerLogContext serverLogs) {
        if (diagnosis.serverLogSummary() != null) {
            return diagnosis;
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        boolean used = serverLogs.enabled() && serverLogs.hasMatches();
        fallback.put("used", used);
        fallback.put("summary", serverLogs.summary());

        String matchedRequest = serverLogs.suspectedRequestMatches().stream()
                .map(match -> String.valueOf(match.getOrDefault("path", "")))
                .filter(path -> !path.isBlank())
                .findFirst()
                .orElse("");
        if (!matchedRequest.isBlank()) {
            fallback.put("matchedRequest", matchedRequest);
        }

        List<String> topEvidence = serverLogs.matchedLines().stream()
                .limit(3)
                .toList();
        fallback.put("topEvidence", topEvidence);

        return new BugDiagnosis(
                diagnosis.rootCause(),
                diagnosis.bugReport(),
                diagnosis.reproductionSteps(),
                fallback
        );
    }

    private <T> T parseResponse(String raw, Class<T> type) {
        String json = raw.strip();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) {
                json = json.substring(firstNewline + 1);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.strip();
        }

        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Initial parse failed for {}, attempting truncated JSON repair", type.getSimpleName());
        }

        String repaired = repairTruncatedJson(json);
        try {
            return objectMapper.readValue(repaired, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI response as {} even after repair. Raw (first 500 chars): {}",
                    type.getSimpleName(), json.substring(0, Math.min(json.length(), 500)));
            throw new RuntimeException("AI returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private String repairTruncatedJson(String json) {
        int lastGood = -1;
        boolean inString = false;
        boolean escaped = false;
        int braceDepth = 0;
        int bracketDepth = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') {
                inString = !inString;
                if (!inString) {
                    lastGood = i;
                }
                continue;
            }
            if (inString) {
                continue;
            }
            switch (c) {
                case '{' -> braceDepth++;
                case '}' -> {
                    braceDepth--;
                    lastGood = i;
                }
                case '[' -> bracketDepth++;
                case ']' -> {
                    bracketDepth--;
                    lastGood = i;
                }
                case ',' -> lastGood = i - 1;
                default -> {
                    // no-op
                }
            }
        }

        StringBuilder sb;
        if (inString) {
            int lastQuote = json.lastIndexOf('"');
            sb = new StringBuilder(json.substring(0, Math.max(lastQuote, 0)));
            sb.append('"');
        } else if (lastGood >= 0 && lastGood < json.length() - 1) {
            sb = new StringBuilder(json.substring(0, lastGood + 1));
        } else {
            sb = new StringBuilder(json);
        }

        String trimmed = sb.toString().stripTrailing();
        if (trimmed.endsWith(",")) {
            sb = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        }

        braceDepth = 0;
        bracketDepth = 0;
        inString = false;
        escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) { continue; }
            switch (c) {
                case '{' -> braceDepth++;
                case '}' -> braceDepth--;
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth--;
                default -> {
                    // no-op
                }
            }
        }

        for (int i = 0; i < bracketDepth; i++) {
            sb.append(']');
        }
        for (int i = 0; i < braceDepth; i++) {
            sb.append('}');
        }

        log.info("Repaired truncated JSON: added {} closing brackets, {} closing braces", bracketDepth, braceDepth);
        return sb.toString();
    }

    private record CachedResponse(String raw, Instant expiresAt) {}
}
