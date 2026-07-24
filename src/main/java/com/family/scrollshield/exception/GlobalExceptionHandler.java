package com.family.scrollshield.exception;

import com.family.scrollshield.dto.response.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Set<String> CONFLICT_ERRORS = Set.of(
            "LEASE_CONFLICT", "QUOTA_EXCEEDED", "BEDTIME_WINDOW", "EXTENSION_DENIED"
    );

    private static final Set<String> NOT_FOUND_ERRORS = Set.of(
            "MEMBER_NOT_FOUND", "LEASE_NOT_FOUND", "INVALID_LEASE_TOKEN", "EXTENSION_NOT_FOUND"
    );

    @ExceptionHandler(IdempotentDuplicateException.class)
    public ResponseEntity<Object> handleIdempotentDuplicate(IdempotentDuplicateException ex) {
        return ResponseEntity.status(HttpStatus.OK).body(ex.getDuplicateResponse());
    }

    @ExceptionHandler(ScrollShieldException.class)
    public ResponseEntity<ErrorResponse> handleScrollShieldException(ScrollShieldException ex) {
        HttpStatus status;
        String errorCode = ex.getErrorCode();

        if (CONFLICT_ERRORS.contains(errorCode)) {
            status = HttpStatus.CONFLICT;
        } else if (NOT_FOUND_ERRORS.contains(errorCode)) {
            status = HttpStatus.NOT_FOUND;
        } else if ("DUPLICATE_REQUEST".equals(errorCode)) {
            status = HttpStatus.OK;
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        ErrorResponse errorResponse = new ErrorResponse(
                errorCode,
                ex.getMessage(),
                Instant.now(),
                null
        );

        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> details = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            details.put(fieldName, errorMessage);
        });

        ErrorResponse errorResponse = new ErrorResponse(
                "VALIDATION_ERROR",
                "Validation failed",
                Instant.now(),
                details
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                "INTERNAL_ERROR",
                ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred",
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
