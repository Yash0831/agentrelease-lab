package com.agentreleaselab.api;

import com.agentreleaselab.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/** Stable error envelope: {error, code, correlationId}. Never leaks stack traces. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest req) {
        String cid = correlationId();
        log.warn("API error {} {} code={} cid={}", req.getMethod(), req.getRequestURI(), e.getCode(), cid);
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "error", e.getMessage(), "code", e.getCode(), "correlationId", cid));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest req) {
        String cid = correlationId();
        log.error("Unexpected error {} {} cid={}", req.getMethod(), req.getRequestURI(), cid, e);
        return ResponseEntity.internalServerError().body(Map.of(
                "error", "Internal error", "code", "INTERNAL_ERROR", "correlationId", cid));
    }

    private String correlationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
