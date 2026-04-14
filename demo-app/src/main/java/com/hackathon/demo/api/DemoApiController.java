package com.hackathon.demo.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoApiController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "tracecraft-demo-app");
    }

    @GetMapping("/slow")
    public Map<String, Object> slowEndpoint() throws InterruptedException {
        Thread.sleep(2500L);
        return Map.of(
                "status", "ok",
                "detail", "Slow endpoint completed after a simulated downstream delay."
        );
    }

    @GetMapping("/fail-500")
    public Map<String, Object> fail500() {
        throw new IllegalStateException("Simulated database connection failure while creating the order.");
    }

    @GetMapping(value = "/malformed-json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> malformedJson() {
        return ResponseEntity.ok("{\"status\":\"broken\",\"payload\":");
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> payload) {
        String email = payload.get("email") == null ? "" : String.valueOf(payload.get("email")).trim();
        if (email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("A valid email address is required.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("email", email);
        result.put("message", "Validation succeeded.");
        return result;
    }
}
