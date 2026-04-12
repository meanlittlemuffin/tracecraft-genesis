package com.hackathon.sessionreplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AIService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private static final String API_URL = "https://completions.me/api/v1/chat/completions";

    public AIService(
            @Value("${completions.api-key}") String apiKey,
            @Value("${completions.base-url}") String baseUrl,
            @Value("${completions.model}") String model) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String analyzeRecording(Map<String, Object> recording) {
        String prompt = """
            Analyze the following session recording and provide a structured summary:
            
            """ + formatRecording(recording) + """
            
            Format your response as:
            1. Summary: Brief overview of what happened
            2. Key Events: List of significant events
            3. Potential Issues: Any errors or warnings detected
            """;
        return callAI(prompt);
    }

    public String generateReport(Map<String, Object> recording) {
        String prompt = """
            Generate a human-readable bug report from this session recording:
            
            """ + formatRecording(recording) + """
            
            Include:
            - Issue Description
            - Timeline of Events
            - Network Activity Summary
            - Console Logs (if any errors)
            - Steps to Reproduce
            """;
        return callAI(prompt);
    }

    public String analyzeRootCause(Map<String, Object> recording) {
        String prompt = """
            Analyze this session recording and identify the most likely root cause:
            
            """ + formatRecording(recording) + """
            
            Provide:
            - Primary Root Cause (with confidence percentage)
            - Secondary Factors
            - Evidence from the recording
            """;
        return callAI(prompt);
    }

    public String generateReproductionSteps(Map<String, Object> recording) {
        String prompt = """
            Based on this session recording, generate curl commands to reproduce the issue:
            
            """ + formatRecording(recording) + """
            
            Include:
            - curl commands for API calls
            - Request headers and body
            - Expected vs actual responses
            """;
        return callAI(prompt);
    }

    private String formatRecording(Map<String, Object> recording) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(recording);
        } catch (JsonProcessingException e) {
            return recording.toString();
        }
    }

    private String callAI(String prompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 2048);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    API_URL,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
            }
            return "No response from AI";
        } catch (Exception e) {
            return "Error calling AI: " + e.getMessage();
        }
    }
}