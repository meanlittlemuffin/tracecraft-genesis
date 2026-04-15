package com.hackathon.sessionreplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.sessionreplay.config.TracecraftProperties;
import com.hackathon.sessionreplay.service.ServerLogService.ServerLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IncidentPacketService {

    private static final Logger log = LoggerFactory.getLogger(IncidentPacketService.class);
    private static final int DEFAULT_SLOW_REQUEST_MS = 1200;
    private static final int MIN_TIMELINE_LIMIT = 12;
    private static final int MIN_NETWORK_LIMIT = 6;
    private static final int MIN_BROWSER_LIMIT = 6;
    private static final int MIN_SERVER_LINES = 12;

    private final ObjectMapper objectMapper;
    private final TracecraftProperties properties;

    public IncidentPacketService(ObjectMapper objectMapper, TracecraftProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public IncidentPacket buildPacket(Map<String, Object> recording, ServerLogContext serverLogContext, PacketMode mode) {
        TracecraftProperties.Ai aiConfig = properties.getAi();
        List<Map<String, Object>> sortedEvents = asListOfMaps(recording.get("events")).stream()
                .sorted(Comparator.comparingLong(event -> asLong(event.get("timestamp"), 0L)))
                .toList();

        int timelineLimit = mode == PacketMode.NETWORK_BOTTLENECKS ? 20 : 30;
        int networkLimit = mode == PacketMode.NETWORK_BOTTLENECKS ? 15 : 12;
        int browserLimit = mode == PacketMode.NETWORK_BOTTLENECKS ? 8 : 12;
        int serverLineLimit = serverLogContext.hasMatches()
                ? Math.min(properties.getLogs().getMaxLinesForAi(), 100)
                : 0;
        int snippetLimit = 280;
        List<String> truncationInfo = new ArrayList<>();

        IncidentPacket packet = buildPacketInternal(
                recording,
                sortedEvents,
                serverLogContext,
                mode,
                timelineLimit,
                networkLimit,
                browserLimit,
                serverLineLimit,
                snippetLimit,
                truncationInfo
        );

        while (packet.charCount() > aiConfig.getTargetPacketChars()
                && canTrim(timelineLimit, networkLimit, browserLimit, serverLineLimit, snippetLimit)) {
            if (serverLineLimit > MIN_SERVER_LINES) {
                serverLineLimit = Math.max(MIN_SERVER_LINES, serverLineLimit - 12);
                truncationInfo.add("Trimmed server log context to stay within the AI packet budget.");
            } else if (timelineLimit > MIN_TIMELINE_LIMIT) {
                timelineLimit = Math.max(MIN_TIMELINE_LIMIT, timelineLimit - 4);
                truncationInfo.add("Trimmed the timeline to the highest-signal events.");
            } else if (networkLimit > MIN_NETWORK_LIMIT) {
                networkLimit = Math.max(MIN_NETWORK_LIMIT, networkLimit - 2);
                truncationInfo.add("Trimmed lower-priority network calls.");
            } else if (browserLimit > MIN_BROWSER_LIMIT) {
                browserLimit = Math.max(MIN_BROWSER_LIMIT, browserLimit - 2);
                truncationInfo.add("Trimmed lower-priority browser evidence.");
            } else {
                snippetLimit = Math.max(120, snippetLimit - 40);
                truncationInfo.add("Shortened request, response, and log snippets.");
            }

            packet = buildPacketInternal(
                    recording,
                    sortedEvents,
                    serverLogContext,
                    mode,
                    timelineLimit,
                    networkLimit,
                    browserLimit,
                    serverLineLimit,
                    snippetLimit,
                    truncationInfo
            );
        }

        if (packet.charCount() > aiConfig.getMaxPacketChars()) {
            serverLineLimit = Math.min(serverLineLimit, MIN_SERVER_LINES);
            timelineLimit = Math.min(timelineLimit, MIN_TIMELINE_LIMIT);
            networkLimit = Math.min(networkLimit, MIN_NETWORK_LIMIT);
            browserLimit = Math.min(browserLimit, MIN_BROWSER_LIMIT);
            snippetLimit = Math.min(snippetLimit, 120);
            truncationInfo.add("Applied the minimum packet layout to stay under the hard AI request cap.");
            packet = buildPacketInternal(
                    recording,
                    sortedEvents,
                    serverLogContext,
                    mode,
                    timelineLimit,
                    networkLimit,
                    browserLimit,
                    serverLineLimit,
                    snippetLimit,
                    truncationInfo
            );
        }

        log.info("Built {} incident packet with {} chars", mode.name().toLowerCase(Locale.ROOT), packet.charCount());
        return packet;
    }

    private boolean canTrim(int timelineLimit, int networkLimit, int browserLimit, int serverLineLimit, int snippetLimit) {
        return timelineLimit > MIN_TIMELINE_LIMIT
                || networkLimit > MIN_NETWORK_LIMIT
                || browserLimit > MIN_BROWSER_LIMIT
                || serverLineLimit > MIN_SERVER_LINES
                || snippetLimit > 120;
    }

    private IncidentPacket buildPacketInternal(
            Map<String, Object> recording,
            List<Map<String, Object>> sortedEvents,
            ServerLogContext serverLogContext,
            PacketMode mode,
            int timelineLimit,
            int networkLimit,
            int browserLimit,
            int serverLineLimit,
            int snippetLimit,
            List<String> truncationInfo
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisMode", mode.name().toLowerCase(Locale.ROOT));
        payload.put("sessionSummary", buildSessionSummary(recording, sortedEvents, serverLogContext));
        payload.put("highSignalTimeline", buildTimeline(sortedEvents, timelineLimit, snippetLimit, mode));
        payload.put("networkEvidence", buildNetworkEvidence(sortedEvents, networkLimit, snippetLimit));
        payload.put("browserEvidence", buildBrowserEvidence(recording, sortedEvents, browserLimit, snippetLimit, mode));

        if (mode != PacketMode.NETWORK_BOTTLENECKS) {
            payload.put("serverLogContext", buildServerLogPayload(serverLogContext, serverLineLimit, snippetLimit));
        }

        Map<String, Object> correlationHints = buildCorrelationHints(
                asListOfMaps(payload.get("networkEvidence")),
                serverLogContext
        );
        if (!correlationHints.isEmpty()) {
            payload.put("correlationHints", correlationHints);
        }

        Map<String, Object> performanceSummary = buildPerformanceSummary(recording);
        if (!performanceSummary.isEmpty()) {
            payload.put("performanceSummary", performanceSummary);
        }

        if (!truncationInfo.isEmpty()) {
            payload.put("truncationInfo", new ArrayList<>(truncationInfo));
        }

        String json = toJson(payload);
        return new IncidentPacket(payload, json, sha256(json), json.length());
    }

    private Map<String, Object> buildSessionSummary(
            Map<String, Object> recording,
            List<Map<String, Object>> sortedEvents,
            ServerLogContext serverLogContext
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> metadata = asMap(recording.get("metadata"));
        Map<String, Object> stats = asMap(recording.get("stats"));
        String url = asString(recording.get("url"));
        long start = asLong(metadata.get("startTime"), sortedEvents.isEmpty()
                ? Instant.now().toEpochMilli()
                : asLong(sortedEvents.get(0).get("timestamp"), Instant.now().toEpochMilli()));
        long stop = asLong(metadata.get("stopTime"), start);

        summary.put("startUrl", url);
        summary.put("host", extractHost(url));
        summary.put("startTime", start);
        summary.put("stopTime", stop);
        summary.put("durationMs", Math.max(0L, stop - start));
        summary.put("userAgent", summarize(asString(recording.get("userAgent")), 120));
        summary.put("eventCounts", stats);
        summary.put("serverLogsEnabled", serverLogContext.enabled());
        summary.put("serverLogMatches", serverLogContext.matchedLines().size());
        summary.put("serverLogSummary", serverLogContext.summary());
        return summary;
    }

    private List<Map<String, Object>> buildNetworkEvidence(List<Map<String, Object>> events, int limit, int snippetLimit) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (!"network".equals(asString(event.get("type")))) {
                continue;
            }

            int status = asInt(event.get("status"), -1);
            long duration = asLong(event.get("duration"), 0L);
            boolean highSignal = status >= 400 || duration >= DEFAULT_SLOW_REQUEST_MS || !asString(event.get("error")).isBlank();
            if (!highSignal) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", asLong(event.get("timestamp"), 0L));
            item.put("method", asString(event.get("method")));
            item.put("url", summarize(asString(event.get("url")), 140));
            item.put("path", extractPath(asString(event.get("url"))));
            item.put("status", status);
            item.put("durationMs", duration);
            item.put("error", summarize(asString(event.get("error")), snippetLimit));

            String requestBody = summarize(asString(event.get("requestBody")), snippetLimit);
            String responseBody = summarize(asString(event.get("responseBody")), snippetLimit);
            if (!requestBody.isBlank()) {
                item.put("requestBodySnippet", requestBody);
            }
            if (!responseBody.isBlank() && status >= 400) {
                item.put("responseBodySnippet", responseBody);
            }

            candidates.add(item);
        }

        candidates.sort(
                Comparator.comparingInt((Map<String, Object> item) -> asInt(item.get("status"), 0) >= 500 ? 0 : asInt(item.get("status"), 0) >= 400 ? 1 : 2)
                        .thenComparing(Comparator.comparingLong((Map<String, Object> item) -> asLong(item.get("durationMs"), 0L)).reversed())
        );

        if (candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(0, limit));
        }
        return candidates;
    }

    private List<Map<String, Object>> buildBrowserEvidence(
            Map<String, Object> recording,
            List<Map<String, Object>> events,
            int limit,
            int snippetLimit,
            PacketMode mode
    ) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String type = asString(event.get("type"));
            if ("error".equals(type)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "error");
                item.put("timestamp", asLong(event.get("timestamp"), 0L));
                item.put("message", summarize(asString(event.get("message")), snippetLimit));
                item.put("stack", summarize(asString(event.get("stack")), snippetLimit));
                item.put("pageUrl", summarize(asString(event.get("pageUrl")), 120));
                candidates.add(item);
            } else if ("console".equals(type)
                    && ("error".equalsIgnoreCase(asString(event.get("level")))
                    || "warn".equalsIgnoreCase(asString(event.get("level"))))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "console");
                item.put("timestamp", asLong(event.get("timestamp"), 0L));
                item.put("level", asString(event.get("level")));
                item.put("message", summarize(asString(event.get("message")), snippetLimit));
                item.put("pageUrl", summarize(asString(event.get("pageUrl")), 120));
                candidates.add(item);
            }
        }

        if (mode != PacketMode.NETWORK_BOTTLENECKS) {
            for (Map<String, Object> rageClick : asListOfMaps(recording.get("rageClicks"))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "rage_click");
                item.put("timestamp", asLong(rageClick.get("startTimestamp"), 0L));
                item.put("element", summarize(asString(rageClick.get("element")), 120));
                item.put("clickCount", asInt(rageClick.get("clickCount"), 0));
                item.put("pageUrl", summarize(asString(rageClick.get("pageUrl")), 120));
                candidates.add(item);
            }
        }

        candidates.sort(Comparator.comparingLong((Map<String, Object> item) -> asLong(item.get("timestamp"), 0L)).reversed());
        if (candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(0, limit));
        }
        return candidates;
    }

    private List<Map<String, Object>> buildTimeline(
            List<Map<String, Object>> events,
            int limit,
            int snippetLimit,
            PacketMode mode
    ) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String type = asString(event.get("type"));
            boolean highSignal = switch (type) {
                case "error", "form_submission" -> true;
                case "console" -> "error".equalsIgnoreCase(asString(event.get("level")))
                        || "warn".equalsIgnoreCase(asString(event.get("level")));
                case "network" -> asInt(event.get("status"), 0) >= 400
                        || asLong(event.get("duration"), 0L) >= DEFAULT_SLOW_REQUEST_MS
                        || !asString(event.get("error")).isBlank();
                case "navigation" -> mode != PacketMode.NETWORK_BOTTLENECKS;
                default -> false;
            };

            if (!highSignal) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", asLong(event.get("timestamp"), 0L));
            item.put("type", type);
            item.put("summary", summarizeTimelineEntry(event, snippetLimit));
            String pageUrl = summarize(asString(event.get("pageUrl")), 120);
            if (!pageUrl.isBlank()) {
                item.put("pageUrl", pageUrl);
            }
            candidates.add(item);
        }

        candidates.sort(Comparator.comparingLong(item -> asLong(item.get("timestamp"), 0L)));
        if (candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(Math.max(0, candidates.size() - limit), candidates.size()));
        }
        return candidates;
    }

    private Map<String, Object> buildServerLogPayload(ServerLogContext context, int serverLineLimit, int snippetLimit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", context.enabled());
        payload.put("sourcePath", context.sourcePath());
        payload.put("timeWindow", context.timeWindow());
        payload.put("summary", context.summary());
        payload.put("truncated", context.truncated());
        payload.put("suspectedRequestMatches", context.suspectedRequestMatches());

        if (context.hasMatches() && serverLineLimit > 0) {
            List<String> lines = context.matchedLines();
            int fromIndex = Math.max(0, lines.size() - serverLineLimit);
            payload.put("matchedLines", lines.subList(fromIndex, lines.size()).stream()
                    .map(line -> summarize(line, snippetLimit))
                    .toList());
        } else {
            payload.put("matchedLines", List.of());
        }

        return payload;
    }

    private Map<String, Object> buildCorrelationHints(List<Map<String, Object>> networkEvidence, ServerLogContext context) {
        Map<String, Object> hints = new LinkedHashMap<>();
        List<String> paths = networkEvidence.stream()
                .map(item -> asString(item.get("path")))
                .filter(path -> !path.isBlank())
                .distinct()
                .limit(8)
                .toList();

        if (!paths.isEmpty()) {
            hints.put("networkPaths", paths);
        }
        if (!context.suspectedRequestMatches().isEmpty()) {
            hints.put("suspectedServerMatches", context.suspectedRequestMatches());
        }
        if (context.hasMatches()) {
            hints.put("serverLogMatchedLines", context.matchedLines().size());
        }
        return hints;
    }

    private Map<String, Object> buildPerformanceSummary(Map<String, Object> recording) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> webVitals = asMap(recording.get("webVitals"));
        if (!webVitals.isEmpty()) {
            summary.put("webVitals", webVitals);
        }
        summary.put("longTaskCount", asList(recording.get("longTasks")).size());
        return summary;
    }

    private String summarizeTimelineEntry(Map<String, Object> event, int snippetLimit) {
        String type = asString(event.get("type"));
        return switch (type) {
            case "network" -> "%s %s -> %s (%sms)".formatted(
                    asString(event.get("method")),
                    extractPath(asString(event.get("url"))),
                    asString(event.get("status")),
                    asString(event.get("duration"))
            );
            case "error" -> summarize(asString(event.get("message")), snippetLimit);
            case "console" -> "%s: %s".formatted(asString(event.get("level")), summarize(asString(event.get("message")), snippetLimit));
            case "form_submission" -> "%s %s".formatted(asString(event.get("method")), summarize(asString(event.get("action")), 100));
            case "navigation" -> "%s -> %s".formatted(asString(event.get("navigationType")), summarize(asString(event.get("url")), 100));
            default -> summarize(event.toString(), snippetLimit);
        };
    }

    private String summarize(String value, int limit) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= limit) {
            return collapsed;
        }
        return collapsed.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize incident packet", e);
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String extractPath(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery();
            return query == null || query.isBlank() ? path : path + "?" + query;
        } catch (Exception e) {
            return url;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long asLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public enum PacketMode {
        ANALYZE,
        NETWORK_BOTTLENECKS,
        BUG_DIAGNOSIS
    }

    public record IncidentPacket(Map<String, Object> payload, String json, String hash, int charCount) {}
}
