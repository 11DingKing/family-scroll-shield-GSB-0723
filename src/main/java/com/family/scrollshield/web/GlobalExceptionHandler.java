package com.family.scrollshield.web;

import com.family.scrollshield.dto.ErrorResponse;
import com.family.scrollshield.service.LeaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain and validation errors to stable HTTP responses. Concurrency conflicts
 * (single-active-lease races, already-used extensions) surface as 409 Conflict, quota
 * exhaustion and bedtime blackout as 409/422 so retrying clients back off correctly.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<ErrorResponse> handleLease(LeaseException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case MEMBER_NOT_FOUND, LEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACTIVE_LEASE_EXISTS, EXTENSION_ALREADY_USED, LEASE_NOT_ACTIVE -> HttpStatus.CONFLICT;
            case QUOTA_EXHAUSTED, BEDTIME_BLACKOUT, EXTENSION_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_TIME_ZONE, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_ERROR", ex.getMessage()));
    }
}
