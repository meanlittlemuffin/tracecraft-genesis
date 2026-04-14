package com.hackathon.sessionreplay.service;

import com.hackathon.sessionreplay.config.TracecraftProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ServerLogService {

    private static final Logger log = LoggerFactory.getLogger(ServerLogService.class);
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)"
    );
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("\\b[\\w.$]+(?:Exception|Error)\\b");
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile("^\\s+(at |\\.\\.\\. \\d+ more)");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );

    private final TracecraftProperties properties;

    public ServerLogService(TracecraftProperties properties) {
        this.properties = properties;
    }

    public ServerLogContext buildContext(Map<String, Object> recording) {
        TracecraftProperties.Logs logsConfig = properties.getLogs();
        String configuredPath = logsConfig.getFilePath();

        if (!logsConfig.isEnabled()) {
            return ServerLogContext.disabled(configuredPath, "Server log enrichment is disabled.");
        }

        String host = extractHost(recording);
        if (host.isBlank() || !isDemoHost(host, logsConfig.getDemoHosts())) {
            return ServerLogContext.disabled(configuredPath, "Server log enrichment skipped for host `" + host + "`.");
        }

        if (configuredPath == null || configuredPath.isBlank()) {
            return ServerLogContext.disabled("", "No server log file path is configured.");
        }

        Path logPath = Paths.get(configuredPath).toAbsolutePath().normalize();
        if (!Files.exists(logPath)) {
            return ServerLogContext.disabled(logPath.toString(), "Configured server log file was not found.");
        }
        if (!Files.isRegularFile(logPath)) {
            return ServerLogContext.disabled(logPath.toString(), "Configured server log path is not a file.");
        }

        TimeWindow timeWindow = resolveTimeWindow(recording, logsConfig.getLookbackSeconds());
        List<RequestCandidate> candidates = collectRequestCandidates(recording);

        try {
            TailRead tailRead = readTail(logPath, logsConfig.getMaxBytesRead());
            MatchResult matchResult = matchLogLines(tailRead.content(), timeWindow, candidates, logsConfig.getMaxLinesForAi());

            Map<String, Object> timeWindowMap = new LinkedHashMap<>();
            timeWindowMap.put("startEpochMs", timeWindow.startEpochMs());
            timeWindowMap.put("endEpochMs", timeWindow.endEpochMs());
            timeWindowMap.put("paddingSeconds", logsConfig.getLookbackSeconds());

            String summary = buildSummary(matchResult, candidates, logPath, tailRead.truncated());
            return new ServerLogContext(
                    true,
                    logPath.toString(),
                    timeWindowMap,
                    summary,
                    matchResult.matchedLines(),
                    buildSuspectedMatches(candidates),
                    matchResult.truncated() || tailRead.truncated()
            );
        } catch (IOException e) {
            log.warn("Failed to read configured server log file {}: {}", logPath, e.getMessage());
            return ServerLogContext.disabled(logPath.toString(), "Failed to read server log file: " + e.getMessage());
        }
    }

    private boolean isDemoHost(String host, List<String> demoHosts) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return demoHosts.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private String extractHost(Map<String, Object> recording) {
        String url = asString(recording.get("url"));
        if (url.isBlank()) {
            url = asString(asMap(recording.get("metadata")).get("startUrl"));
        }
        if (url.isBlank()) {
            return "";
        }
        try {
            return Optional.ofNullable(URI.create(url).getHost()).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private TimeWindow resolveTimeWindow(Map<String, Object> recording, int paddingSeconds) {
        Map<String, Object> metadata = asMap(recording.get("metadata"));
        long start = asLong(metadata.get("startTime"), Instant.now().toEpochMilli());
        long stop = asLong(metadata.get("stopTime"), start);
        long paddingMs = paddingSeconds * 1000L;
        return new TimeWindow(Math.max(0L, start - paddingMs), stop + paddingMs);
    }

    private List<RequestCandidate> collectRequestCandidates(Map<String, Object> recording) {
        List<Map<String, Object>> events = asListOfMaps(recording.get("events"));
        List<RequestCandidate> candidates = new ArrayList<>();

        for (Map<String, Object> event : events) {
            if (!"network".equals(asString(event.get("type")))) {
                continue;
            }

            int status = asInt(event.get("status"), -1);
            long duration = asLong(event.get("duration"), 0L);
            boolean highSignal = status >= 400 || duration >= 1200 || !asString(event.get("error")).isBlank();
            if (!highSignal) {
                continue;
            }

            String path = extractPath(asString(event.get("url")));
            if (path.isBlank()) {
                continue;
            }

            candidates.add(new RequestCandidate(
                    asString(event.get("method")).toUpperCase(Locale.ROOT),
                    path,
                    status,
                    duration,
                    asLong(event.get("timestamp"), 0L)
            ));
        }

        if (candidates.isEmpty()) {
            for (Map<String, Object> event : events) {
                if (!"network".equals(asString(event.get("type")))) {
                    continue;
                }
                String path = extractPath(asString(event.get("url")));
                if (!path.isBlank()) {
                    candidates.add(new RequestCandidate(
                            asString(event.get("method")).toUpperCase(Locale.ROOT),
                            path,
                            asInt(event.get("status"), -1),
                            asLong(event.get("duration"), 0L),
                            asLong(event.get("timestamp"), 0L)
                    ));
                }
                if (candidates.size() >= 5) {
                    break;
                }
            }
        }

        candidates.sort(
                Comparator.comparingInt((RequestCandidate candidate) -> candidate.status() >= 500 ? 0 : candidate.status() >= 400 ? 1 : 2)
                        .thenComparing(Comparator.comparingLong(RequestCandidate::duration).reversed())
        );

        LinkedHashMap<String, RequestCandidate> deduped = new LinkedHashMap<>();
        for (RequestCandidate candidate : candidates) {
            String key = candidate.method() + " " + candidate.path() + " " + candidate.status();
            deduped.putIfAbsent(key, candidate);
        }
        return new ArrayList<>(deduped.values());
    }

    private TailRead readTail(Path logPath, int maxBytesRead) throws IOException {
        long size = Files.size(logPath);
        long start = Math.max(0L, size - maxBytesRead);
        byte[] bytes;

        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            file.seek(start);
            bytes = new byte[(int) (size - start)];
            file.readFully(bytes);
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        if (start > 0) {
            int newline = content.indexOf('\n');
            if (newline >= 0 && newline < content.length() - 1) {
                content = content.substring(newline + 1);
            }
        }

        return new TailRead(content, start > 0);
    }

    private MatchResult matchLogLines(String content, TimeWindow timeWindow, List<RequestCandidate> candidates, int maxLines) {
        String[] lines = content.split("\\R");
        List<ScoredLine> scoredLines = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            int score = scoreLine(lines[i], timeWindow, candidates);
            if (score > 0) {
                scoredLines.add(new ScoredLine(i, score));
            }
        }

        LinkedHashSet<Integer> selectedIndexes = new LinkedHashSet<>();
        for (ScoredLine scoredLine : scoredLines) {
            selectedIndexes.add(scoredLine.index());
            addContextLines(lines, scoredLine.index(), selectedIndexes);
        }

        List<String> matchedLines = selectedIndexes.stream()
                .sorted()
                .map(index -> lines[index])
                .toList();

        boolean truncated = matchedLines.size() > maxLines;
        if (truncated) {
            matchedLines = matchedLines.subList(Math.max(0, matchedLines.size() - maxLines), matchedLines.size());
        }

        return new MatchResult(matchedLines, truncated, scoredLines.size());
    }

    private int scoreLine(String line, TimeWindow timeWindow, List<RequestCandidate> candidates) {
        if (line == null || line.isBlank()) {
            return 0;
        }

        int score = 0;
        String normalized = line.toLowerCase(Locale.ROOT);
        Instant timestamp = parseTimestamp(line);
        if (timestamp != null) {
            long epochMs = timestamp.toEpochMilli();
            if (epochMs >= timeWindow.startEpochMs() && epochMs <= timeWindow.endEpochMs()) {
                score += 3;
            }
        }

        if (normalized.contains(" error ") || normalized.startsWith("error") || normalized.contains("error:")) {
            score += 3;
        }
        if (normalized.contains(" warn ") || normalized.startsWith("warn") || normalized.contains("warn:")) {
            score += 2;
        }
        if (EXCEPTION_PATTERN.matcher(line).find()) {
            score += 4;
        }
        if (normalized.contains("status=500") || normalized.contains("status=400") || normalized.contains("-> 500") || normalized.contains("-> 400")) {
            score += 2;
        }

        for (RequestCandidate candidate : candidates) {
            if (!candidate.path().isBlank() && line.contains(candidate.path())) {
                score += 4;
            }
            if (!candidate.method().isBlank() && normalized.contains(candidate.method().toLowerCase(Locale.ROOT))) {
                score += 1;
            }
            if (candidate.status() > 0 && normalized.contains(String.valueOf(candidate.status()))) {
                score += 1;
            }
        }

        return score;
    }

    private void addContextLines(String[] lines, int index, Set<Integer> selectedIndexes) {
        if (index > 0) {
            selectedIndexes.add(index - 1);
        }

        for (int cursor = index + 1; cursor < Math.min(lines.length, index + 8); cursor++) {
            String current = lines[cursor];
            if (current.isBlank()) {
                break;
            }
            if (TIMESTAMP_PREFIX.matcher(current).find() && cursor != index + 1) {
                break;
            }
            if (STACK_TRACE_PATTERN.matcher(current).find()
                    || current.startsWith("Caused by:")
                    || EXCEPTION_PATTERN.matcher(current).find()
                    || current.startsWith("\t")) {
                selectedIndexes.add(cursor);
                continue;
            }
            if (cursor == index + 1) {
                selectedIndexes.add(cursor);
            } else {
                break;
            }
        }
    }

    private String buildSummary(MatchResult matchResult, List<RequestCandidate> candidates, Path logPath, boolean tailTruncated) {
        List<String> parts = new ArrayList<>();
        parts.add("Read `" + logPath.getFileName() + "` and found " + matchResult.anchorMatchCount() + " high-signal log matches.");
        if (!candidates.isEmpty()) {
            parts.add("Matched against " + Math.min(candidates.size(), 5) + " candidate browser requests.");
        }
        if (matchResult.matchedLines().isEmpty()) {
            parts.add("No log lines matched the current browser session window.");
        }
        if (matchResult.truncated() || tailTruncated) {
            parts.add("Server log context was trimmed to stay within the AI budget.");
        }
        return String.join(" ", parts);
    }

    private List<Map<String, Object>> buildSuspectedMatches(List<RequestCandidate> candidates) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (RequestCandidate candidate : candidates.stream().limit(5).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", candidate.method());
            item.put("path", candidate.path());
            item.put("status", candidate.status());
            item.put("durationMs", candidate.duration());
            item.put("timestamp", candidate.timestamp());
            matches.add(item);
        }
        return matches;
    }

    private Instant parseTimestamp(String line) {
        Matcher matcher = TIMESTAMP_PREFIX.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        String raw = matcher.group(1).replace(',', '.');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                if (formatter == DateTimeFormatter.ISO_OFFSET_DATE_TIME) {
                    return OffsetDateTime.parse(raw, formatter).toInstant();
                }
                LocalDateTime localDateTime = LocalDateTime.parse(raw, formatter);
                return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next formatter.
            }
        }
        return null;
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

    public record ServerLogContext(
            boolean enabled,
            String sourcePath,
            Map<String, Object> timeWindow,
            String summary,
            List<String> matchedLines,
            List<Map<String, Object>> suspectedRequestMatches,
            boolean truncated
    ) {
        public static ServerLogContext disabled(String sourcePath, String summary) {
            return new ServerLogContext(false, sourcePath, Map.of(), summary, List.of(), List.of(), false);
        }

        public boolean hasMatches() {
            return enabled && !matchedLines.isEmpty();
        }
    }

    private record TimeWindow(long startEpochMs, long endEpochMs) {}

    private record RequestCandidate(String method, String path, int status, long duration, long timestamp) {}

    private record TailRead(String content, boolean truncated) {}

    private record ScoredLine(int index, int score) {}

    private record MatchResult(List<String> matchedLines, boolean truncated, int anchorMatchCount) {}
}
