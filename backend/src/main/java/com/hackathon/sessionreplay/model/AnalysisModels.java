package com.hackathon.sessionreplay.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * All fields use Object/String types to tolerate Gemini's inconsistent JSON output.
 * Gemini may return numbers as strings, arrays as counts, booleans as strings, etc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisModels {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionAnalysis(
            String summary,
            Object healthScore,
            Object issues,
            Object networkReport,
            Object uxReport,
            Object recommendations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NetworkBottleneckReport(
            String summary,
            Object networkHealthScore,
            Object totalRequests,
            Object failedRequests,
            Object totalDurationMs,
            Object slowEndpoints,
            Object nplusOnePatterns,
            Object parallelizableGroups,
            Object redundantCalls,
            Object largePayloads,
            Object corsPreflights,
            Object compressionIssues,
            Object recommendations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BugDiagnosis(
            Object rootCause,
            Object bugReport,
            Object reproductionSteps,
            Object serverLogSummary
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeFixSuggestion(
            String summary,
            Object confidence,
            Object matchedRoute,
            Object impactedFiles,
            Object whyTheseFiles,
            Object suggestedChange,
            Object beforeSnippet,
            Object afterSnippet,
            Object validationSteps,
            Object disclaimer
    ) {}
}
