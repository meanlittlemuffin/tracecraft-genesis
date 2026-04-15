package com.hackathon.sessionreplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.sessionreplay.config.TracecraftProperties;
import com.hackathon.sessionreplay.model.AnalysisModels.BugDiagnosis;
import com.hackathon.sessionreplay.service.IncidentPacketService.IncidentPacket;
import com.hackathon.sessionreplay.service.ServerLogService.ServerLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeContextService {

    private static final Logger log = LoggerFactory.getLogger(CodeContextService.class);
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern METHOD_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern ROUTE_PATTERN = Pattern.compile("@(?:Request|Get|Post|Put|Delete|Patch)Mapping\\(([^)]*)\\)");
    private static final Pattern ROUTE_VALUE_PATTERN = Pattern.compile("(?:value|path)?\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");
    private static final Pattern STACK_TRACE_CLASS_PATTERN = Pattern.compile("\\bat\\s+[\\w.$]*\\.([A-Z][A-Za-z0-9_]*)\\.");
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(?:Controller|Handler|Service|Application|Exception))\\b");
    private static final Pattern ROUTE_IN_TEXT_PATTERN = Pattern.compile("(/api/[A-Za-z0-9_./?-]+)");

    private final ObjectMapper objectMapper;
    private final TracecraftProperties properties;

    public CodeContextService(ObjectMapper objectMapper, TracecraftProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CodeContext buildContext(
            Map<String, Object> recording,
            IncidentPacket incidentPacket,
            ServerLogContext serverLogs,
            BugDiagnosis diagnosis
    ) {
        TracecraftProperties.CodeContext config = properties.getCodeContext();
        if (!config.isEnabled()) {
            return CodeContext.unsupported("Code context retrieval is disabled.", "", List.of());
        }

        String host = extractHost(recording);
        if (!isDemoHost(host)) {
            return CodeContext.unsupported("Code fix suggestions currently support localhost demo-app sessions only.", "", List.of());
        }

        Path sourceRoot = resolveSourceRoot(config.getSourceRoot());
        if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            return CodeContext.unsupported("Configured demo-app source root was not found: " + sourceRoot, "", List.of());
        }

        String matchedRoute = resolveMatchedRoute(recording, incidentPacket, serverLogs, diagnosis);
        LinkedHashSet<String> classHints = extractClassHints(serverLogs, diagnosis);
        List<Path> javaFiles = listJavaFiles(sourceRoot);
        if (javaFiles.isEmpty()) {
            return CodeContext.unsupported("No demo-app Java source files were found under " + sourceRoot, matchedRoute, List.of());
        }

        List<SnippetCandidate> candidates = new ArrayList<>();
        for (Path file : javaFiles) {
            candidates.addAll(scanFile(file, sourceRoot, matchedRoute, classHints, config.getMaxSnippetLines()));
        }

        if (candidates.isEmpty()) {
            return CodeContext.unsupported("No matching demo-app source snippets were found for the current failure.", matchedRoute, List.of());
        }

        candidates.sort(Comparator.comparingInt(SnippetCandidate::score).reversed());
        List<SourceSnippet> snippets = new ArrayList<>();
        LinkedHashSet<String> seenFiles = new LinkedHashSet<>();
        int maxFiles = Math.max(1, config.getMaxFiles());

        for (SnippetCandidate candidate : candidates) {
            if (seenFiles.contains(candidate.relativePath())) {
                continue;
            }
            snippets.add(candidate.snippet());
            seenFiles.add(candidate.relativePath());
            if (snippets.size() >= maxFiles) {
                break;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchedRoute", matchedRoute);
        payload.put("sourceRoot", sourceRoot.toString());
        payload.put("classHints", new ArrayList<>(classHints));
        payload.put("snippets", snippets.stream().map(this::toMap).toList());
        String json = toJson(payload);

        return new CodeContext(true, matchedRoute, buildSummary(matchedRoute, snippets), snippets, json, sha256(json));
    }

    private List<SnippetCandidate> scanFile(
            Path file,
            Path sourceRoot,
            String matchedRoute,
            LinkedHashSet<String> classHints,
            int maxSnippetLines
    ) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<MethodBlock> methods = parseMethods(lines);
            String relativePath = sourceRoot.relativize(file).toString().replace('\\', '/');
            String fileStem = stripExtension(file.getFileName().toString());
            List<SnippetCandidate> candidates = new ArrayList<>();

            for (MethodBlock method : methods) {
                int score = scoreMethod(method, matchedRoute, classHints, fileStem);
                if (score <= 0) {
                    continue;
                }

                int snippetStart = Math.max(1, method.startLine() - 2);
                int snippetEnd = Math.min(lines.size(), Math.max(method.endLine(), method.startLine() + maxSnippetLines - 1));
                if (snippetEnd - snippetStart + 1 > maxSnippetLines) {
                    snippetEnd = snippetStart + maxSnippetLines - 1;
                }

                String snippetText = buildSnippet(lines, snippetStart, snippetEnd);
                SourceSnippet snippet = new SourceSnippet(
                        relativePath,
                        method.className(),
                        method.methodName(),
                        method.route(),
                        snippetStart,
                        snippetEnd,
                        buildReason(method, matchedRoute, classHints, fileStem),
                        snippetText
                );
                candidates.add(new SnippetCandidate(score, relativePath, snippet));
            }

            return candidates;
        } catch (IOException e) {
            log.warn("Failed to read demo-app source file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    private List<MethodBlock> parseMethods(List<String> lines) {
        List<MethodBlock> methods = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        String currentClass = "";
        String classBasePath = "";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) {
                continue;
            }

            if (line.startsWith("@")) {
                pendingAnnotations.add(line);
                continue;
            }

            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                currentClass = classMatcher.group(1);
                String route = extractRoute(pendingAnnotations);
                if (!route.isBlank()) {
                    classBasePath = route;
                }
                pendingAnnotations.clear();
                continue;
            }

            if (looksLikeMethodSignature(line)) {
                String methodName = extractMethodName(line);
                if (!methodName.isBlank()) {
                    String annotations = String.join(" ", pendingAnnotations);
                    String route = extractRoute(pendingAnnotations);
                    String fullRoute = route.isBlank() ? "" : joinPaths(classBasePath, route);
                    int endLine = findMethodEnd(lines, i);
                    methods.add(new MethodBlock(currentClass, methodName, fullRoute, annotations, i + 1, endLine));
                }
                pendingAnnotations.clear();
                continue;
            }

            if (!line.startsWith("//")) {
                pendingAnnotations.clear();
            }
        }

        return methods;
    }

    private boolean looksLikeMethodSignature(String line) {
        return line.contains("(")
                && line.contains(")")
                && line.endsWith("{")
                && !line.startsWith("if ")
                && !line.startsWith("for ")
                && !line.startsWith("while ")
                && !line.startsWith("switch ")
                && !line.startsWith("catch ")
                && !line.startsWith("return ");
    }

    private String extractMethodName(String line) {
        Matcher matcher = METHOD_PATTERN.matcher(line);
        String methodName = "";
        while (matcher.find()) {
            methodName = matcher.group(1);
        }
        if (List.of("if", "for", "while", "switch", "catch").contains(methodName)) {
            return "";
        }
        return methodName;
    }

    private int findMethodEnd(List<String> lines, int startIndex) {
        int braceDepth = 0;
        boolean started = false;
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (ch == '{') {
                    braceDepth++;
                    started = true;
                } else if (ch == '}') {
                    braceDepth--;
                    if (started && braceDepth <= 0) {
                        return i + 1;
                    }
                }
            }
        }
        return Math.min(lines.size(), startIndex + 1);
    }

    private int scoreMethod(MethodBlock method, String matchedRoute, LinkedHashSet<String> classHints, String fileStem) {
        int score = 0;
        if (!matchedRoute.isBlank() && !method.route().isBlank()) {
            if (normalizePath(method.route()).equals(normalizePath(matchedRoute))) {
                score += 120;
            } else if (normalizePath(method.route()).contains(normalizePath(matchedRoute))
                    || normalizePath(matchedRoute).contains(normalizePath(method.route()))) {
                score += 80;
            }
        }

        if (classHints.contains(method.className()) || classHints.contains(fileStem)) {
            score += 60;
        }

        if (method.annotations().contains("@ExceptionHandler")) {
            score += 25;
        }

        if (method.annotations().contains("@RequestMapping")
                || method.annotations().contains("@GetMapping")
                || method.annotations().contains("@PostMapping")) {
            score += 15;
        }

        return score;
    }

    private String buildReason(MethodBlock method, String matchedRoute, LinkedHashSet<String> classHints, String fileStem) {
        List<String> reasons = new ArrayList<>();
        if (!matchedRoute.isBlank() && !method.route().isBlank()) {
            reasons.add("route match");
        }
        if (classHints.contains(method.className()) || classHints.contains(fileStem)) {
            reasons.add("server-log class hint");
        }
        if (method.annotations().contains("@ExceptionHandler")) {
            reasons.add("exception handling path");
        }
        if (reasons.isEmpty()) {
            reasons.add("closest matching demo-app source");
        }
        return String.join(", ", reasons);
    }

    private String extractRoute(List<String> annotations) {
        for (String annotation : annotations) {
            Matcher matcher = ROUTE_PATTERN.matcher(annotation);
            if (!matcher.find()) {
                continue;
            }

            String args = matcher.group(1);
            Matcher valueMatcher = ROUTE_VALUE_PATTERN.matcher(args);
            while (valueMatcher.find()) {
                String route = valueMatcher.group(1) != null ? valueMatcher.group(1) : valueMatcher.group(2);
                if (route != null && !route.isBlank()) {
                    return route.trim();
                }
            }
        }
        return "";
    }

    private String resolveMatchedRoute(
            Map<String, Object> recording,
            IncidentPacket incidentPacket,
            ServerLogContext serverLogs,
            BugDiagnosis diagnosis
    ) {
        String fromDiagnosis = extractRouteFromDiagnosis(diagnosis);
        if (!fromDiagnosis.isBlank()) {
            return fromDiagnosis;
        }

        String fromServerLogs = serverLogs.suspectedRequestMatches().stream()
                .map(match -> String.valueOf(match.getOrDefault("path", "")))
                .filter(path -> !path.isBlank())
                .findFirst()
                .orElse("");
        if (!fromServerLogs.isBlank()) {
            return fromServerLogs;
        }

        List<Map<String, Object>> networkEvidence = asListOfMaps(incidentPacket.payload().get("networkEvidence"));
        String fromPacket = networkEvidence.stream()
                .map(item -> String.valueOf(item.getOrDefault("path", "")))
                .filter(path -> !path.isBlank())
                .findFirst()
                .orElse("");
        if (!fromPacket.isBlank()) {
            return fromPacket;
        }

        List<Map<String, Object>> events = asListOfMaps(recording.get("events"));
        return events.stream()
                .filter(event -> "network".equals(String.valueOf(event.getOrDefault("type", ""))))
                .map(event -> extractPath(String.valueOf(event.getOrDefault("url", ""))))
                .filter(path -> !path.isBlank())
                .findFirst()
                .orElse("");
    }

    private String extractRouteFromDiagnosis(BugDiagnosis diagnosis) {
        Map<String, Object> serverLogSummary = asMap(diagnosis == null ? null : diagnosis.serverLogSummary());
        String matchedRequest = String.valueOf(serverLogSummary.getOrDefault("matchedRequest", ""));
        if (!matchedRequest.isBlank()) {
            return matchedRequest;
        }

        if (diagnosis != null) {
            for (Object section : new Object[]{diagnosis.rootCause(), diagnosis.bugReport()}) {
                String route = firstRouteInValue(section);
                if (!route.isBlank()) {
                    return route;
                }
            }
        }
        return "";
    }

    private LinkedHashSet<String> extractClassHints(ServerLogContext serverLogs, BugDiagnosis diagnosis) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (String line : serverLogs.matchedLines()) {
            Matcher stackTraceMatcher = STACK_TRACE_CLASS_PATTERN.matcher(line);
            while (stackTraceMatcher.find()) {
                hints.add(stackTraceMatcher.group(1));
            }

            Matcher classMatcher = CLASS_NAME_PATTERN.matcher(line);
            while (classMatcher.find()) {
                hints.add(classMatcher.group(1));
            }
        }

        if (diagnosis != null) {
            for (Object section : new Object[]{diagnosis.rootCause(), diagnosis.bugReport(), diagnosis.serverLogSummary()}) {
                collectClassHints(section, hints);
            }
        }

        return hints;
    }

    private void collectClassHints(Object value, LinkedHashSet<String> hints) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectClassHints(item, hints));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectClassHints(item, hints));
            return;
        }

        String text = value == null ? "" : String.valueOf(value);
        Matcher classMatcher = CLASS_NAME_PATTERN.matcher(text);
        while (classMatcher.find()) {
            hints.add(classMatcher.group(1));
        }
    }

    private String firstRouteInValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                String route = firstRouteInValue(item);
                if (!route.isBlank()) {
                    return route;
                }
            }
            return "";
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String route = firstRouteInValue(item);
                if (!route.isBlank()) {
                    return route;
                }
            }
            return "";
        }

        String text = value == null ? "" : String.valueOf(value);
        Matcher matcher = ROUTE_IN_TEXT_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<Path> listJavaFiles(Path sourceRoot) {
        try (var files = Files.walk(sourceRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to scan demo-app source root {}: {}", sourceRoot, e.getMessage());
            return List.of();
        }
    }

    private boolean isDemoHost(String host) {
        return host != null && properties.getLogs().getDemoHosts().stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(host));
    }

    private String extractHost(Map<String, Object> recording) {
        String url = String.valueOf(recording.getOrDefault("url", ""));
        if (url.isBlank()) {
            url = String.valueOf(asMap(recording.get("metadata")).getOrDefault("startUrl", ""));
        }

        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private Path resolveSourceRoot(String configuredRoot) {
        String resolved = configuredRoot == null ? "" : configuredRoot;
        resolved = resolved.replace("${user.dir}", System.getProperty("user.dir"));
        return Paths.get(resolved).toAbsolutePath().normalize();
    }

    private String extractPath(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getPath() == null ? "" : uri.getPath();
        } catch (Exception e) {
            return "";
        }
    }

    private String joinPaths(String base, String child) {
        if (base == null || base.isBlank()) {
            return normalizePath(child);
        }
        if (child == null || child.isBlank()) {
            return normalizePath(base);
        }
        return normalizePath(base + "/" + child);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private String buildSnippet(List<String> lines, int startLine, int endLine) {
        List<String> snippetLines = new ArrayList<>();
        for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
            snippetLines.add("%4d: %s".formatted(lineNumber, lines.get(lineNumber - 1)));
        }
        return String.join("\n", snippetLines);
    }

    private String buildSummary(String matchedRoute, List<SourceSnippet> snippets) {
        if (snippets.isEmpty()) {
            return "No demo-app source snippets were matched.";
        }
        return "Matched route `%s` to %d demo-app source snippet(s).".formatted(
                matchedRoute.isBlank() ? "(unresolved)" : matchedRoute,
                snippets.size()
        );
    }

    private Map<String, Object> toMap(SourceSnippet snippet) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("filePath", snippet.filePath());
        map.put("className", snippet.className());
        map.put("methodName", snippet.methodName());
        map.put("matchedRoute", snippet.matchedRoute());
        map.put("startLine", snippet.startLine());
        map.put("endLine", snippet.endLine());
        map.put("reason", snippet.reason());
        map.put("snippet", snippet.snippet());
        return map;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize code context", e);
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

    private record MethodBlock(
            String className,
            String methodName,
            String route,
            String annotations,
            int startLine,
            int endLine
    ) {}

    private record SnippetCandidate(int score, String relativePath, SourceSnippet snippet) {}

    public record SourceSnippet(
            String filePath,
            String className,
            String methodName,
            String matchedRoute,
            int startLine,
            int endLine,
            String reason,
            String snippet
    ) {}

    public record CodeContext(
            boolean supported,
            String matchedRoute,
            String summary,
            List<SourceSnippet> snippets,
            String json,
            String hash
    ) {
        public static CodeContext unsupported(String summary, String matchedRoute, List<SourceSnippet> snippets) {
            return new CodeContext(false, matchedRoute, summary, snippets, "{}", "unsupported");
        }
    }
}
