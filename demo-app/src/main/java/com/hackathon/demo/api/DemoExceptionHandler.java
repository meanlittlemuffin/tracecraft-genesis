package com.hackathon.demo.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class DemoExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleServerFailure(IllegalStateException exception, HttpServletRequest request) {
        log.error("Demo app failure for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "SERVER_FAILURE",
                        "message", exception.getMessage(),
                        "path", request.getRequestURI()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleValidationFailure(IllegalArgumentException exception, HttpServletRequest request) {
        log.warn("Validation failed for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", "VALIDATION_ERROR",
                        "message", exception.getMessage(),
                        "path", request.getRequestURI()
                ));
    }
}
