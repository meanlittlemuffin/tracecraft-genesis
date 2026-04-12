package com.hackathon.sessionreplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.sessionreplay.model.AnalysisModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AIService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public SessionAnalysis analyzeSession(Map<String, Object> recording) {
        String data = formatRecording(recording);
        String raw = chatClient.prompt()
                .system("""
                        You are an expert browser session analyst. Analyze recorded browser sessions
                        to identify bugs, performance issues, UX problems, and network bottlenecks.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""")
                .user("Analyze this browser session recording. Respond with a JSON object containing:\n"
                        + "- \"summary\": string overview\n"
                        + "- \"healthScore\": 0-100\n"
                        + "- \"issues\": array of objects with id, category, severity, severityScore, title, description, evidence (array of eventType/timestamp/detail), impact (timeWastedMs/bandwidthWastedBytes/description)\n"
                        + "- \"networkReport\": object with summary, networkHealthScore, totalRequests, failedRequests, totalDurationMs, slowEndpoints, nplusOnePatterns, parallelizableGroups, redundantCalls, largePayloads, corsPreflights, compressionIssues, recommendations\n"
                        + "- \"uxReport\": object with summary, uxHealthScore, rageClicks, deadClicks, navigationUturns, formAbandonmentDetected, frustrationEvents, recommendations\n"
                        + "- \"recommendations\": array of priority/action/effort/codeExample\n\n"
                        + "SESSION DATA:\n" + data)
                .call()
                .content();
        return parseResponse(raw, SessionAnalysis.class);
    }

    public NetworkBottleneckReport analyzeNetworkBottlenecks(Map<String, Object> recording) {
        String data = formatRecording(recording);
        String raw = chatClient.prompt()
                .system("""
                        You are a network performance expert analyzing browser session network data.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""")
                .user("Analyze network calls for bottlenecks. Respond with a JSON object containing:\n"
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
                        + "SESSION DATA:\n" + data)
                .call()
                .content();
        return parseResponse(raw, NetworkBottleneckReport.class);
    }

    public BugDiagnosis diagnoseBug(Map<String, Object> recording) {
        String data = formatRecording(recording);
        String raw = chatClient.prompt()
                .system("""
                        You are a senior software engineer debugging a bug from a browser session recording.
                        The user encountered a bug and recorded their steps. Your job is to analyze the
                        recording, find the root cause, write a bug report, and provide reproduction steps.
                        You MUST respond with ONLY raw JSON. No markdown code fences, no explanation, no text before or after the JSON.""")
                .user("Diagnose the bug in this session recording. Respond with a JSON object containing:\n\n"
                        + "1. \"rootCause\": object with:\n"
                        + "   - \"summary\": one-line root cause\n"
                        + "   - \"confidence\": string like \"85%\"\n"
                        + "   - \"triggerChain\": array of strings describing the event sequence that led to the bug (e.g. \"User clicked Submit\" -> \"POST /api/order returned 500\" -> \"JS error: Cannot read property\")\n"
                        + "   - \"evidence\": array of strings citing specific events from the recording\n"
                        + "   - \"fix\": suggested code or configuration fix\n\n"
                        + "2. \"bugReport\": object with:\n"
                        + "   - \"title\": short bug title\n"
                        + "   - \"description\": detailed description of the bug\n"
                        + "   - \"severity\": CRITICAL/HIGH/MEDIUM/LOW\n"
                        + "   - \"timeline\": array of strings describing key events in chronological order\n"
                        + "   - \"networkActivity\": summary of relevant network calls (failed calls, slow calls, error responses)\n"
                        + "   - \"consoleLogs\": summary of relevant console errors and warnings\n"
                        + "   - \"environment\": user agent, page URL, screen size from metadata\n\n"
                        + "3. \"reproductionSteps\": object with:\n"
                        + "   - \"steps\": array of strings (step-by-step user actions to reproduce)\n"
                        + "   - \"curlCommands\": array of strings (curl commands to reproduce the API calls that failed or triggered the bug)\n"
                        + "   - \"expectedBehavior\": what should have happened\n"
                        + "   - \"actualBehavior\": what actually happened\n\n"
                        + "SESSION DATA:\n" + data)
                .call()
                .content();
        return parseResponse(raw, BugDiagnosis.class);
    }

    private <T> T parseResponse(String raw, Class<T> type) {
        String json = raw.strip();
        // Strip markdown code fences if Gemini wrapped the JSON
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

        // Try parsing as-is first
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Initial parse failed for {}, attempting truncated JSON repair", type.getSimpleName());
        }

        // Attempt to repair truncated JSON by closing open structures
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
        // Find the last complete value boundary (end of string, number, boolean, null, ] or })
        // then close any remaining open brackets/braces
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
                if (!inString) lastGood = i; // end of string
                continue;
            }
            if (inString) continue;
            switch (c) {
                case '{': braceDepth++; break;
                case '}': braceDepth--; lastGood = i; break;
                case '[': bracketDepth++; break;
                case ']': bracketDepth--; lastGood = i; break;
                case ',': lastGood = i - 1; break; // before trailing comma
            }
        }

        // If we're inside a string, truncate to before the last open quote
        StringBuilder sb;
        if (inString) {
            int lastQuote = json.lastIndexOf('"');
            sb = new StringBuilder(json.substring(0, lastQuote));
            sb.append('"'); // close the string
        } else if (lastGood >= 0 && lastGood < json.length() - 1) {
            sb = new StringBuilder(json.substring(0, lastGood + 1));
        } else {
            sb = new StringBuilder(json);
        }

        // Remove any trailing comma
        String trimmed = sb.toString().stripTrailing();
        if (trimmed.endsWith(",")) {
            sb = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        }

        // Recount open braces/brackets in the trimmed string
        braceDepth = 0; bracketDepth = 0; inString = false; escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            switch (c) {
                case '{': braceDepth++; break;
                case '}': braceDepth--; break;
                case '[': bracketDepth++; break;
                case ']': bracketDepth--; break;
            }
        }

        // Close remaining open structures
        for (int i = 0; i < bracketDepth; i++) sb.append(']');
        for (int i = 0; i < braceDepth; i++) sb.append('}');

        log.info("Repaired truncated JSON: added {} closing brackets, {} closing braces", bracketDepth, braceDepth);
        return sb.toString();
    }

    private String formatRecording(Map<String, Object> recording) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(recording);
            if (json.length() > 80000) {
                json = json.substring(0, 80000) + "\n...[truncated, " + json.length() + " total chars]";
            }
            return json;
        } catch (JsonProcessingException e) {
            return recording.toString();
        }
    }
}
