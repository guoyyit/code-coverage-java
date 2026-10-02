package com.github.guoyyit.codecoveragejava.exception;

import java.util.Map;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CoverageApiException.class)
    public ResponseEntity<Map<String, Object>> handle(CoverageApiException exception) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", exception.getStatus(),
                "error", errorName(exception.getStatus()),
                "message", exception.getMessage() == null ? "" : exception.getMessage()
        );
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    private static String errorName(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "ERROR";
        };
    }
}
