package com.family.scrollshield.web;

import com.family.scrollshield.dto.ErrorResponse;
import com.family.scrollshield.service.LeaseException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<ErrorResponse> handleLease(LeaseException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "MEMBER_NOT_FOUND", "LEASE_NOT_FOUND", "PLAN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ACTIVE_LEASE_EXISTS", "DAILY_QUOTA_EXHAUSTED", "BEDTIME_WINDOW",
                 "CROSSED_MIDNIGHT", "EXTENSION_ALREADY_USED",
                 "EXTENSION_BLOCKED_BY_BEDTIME", "PLAN_CLOSED",
                 "SESSION_EXCEEDS_SINGLE_LIMIT", "LEASE_NOT_ACTIVE",
                 "BEDTIME_REQUIRED" -> HttpStatus.CONFLICT;
            case "LEASE_EXPIRED" -> HttpStatus.GONE;
            case "INVALID_TIME_ZONE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getCode(), ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("validation error");
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", msg, Instant.now()));
    }

    @ExceptionHandler({CannotAcquireLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLockConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LOCK_CONFLICT", "资源忙，请重试", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", ex.getMessage(), Instant.now()));
    }
}
