package com.family.scrollshield.exception;

public class IdempotentDuplicateException extends ScrollShieldException {

    private final transient Object duplicateResponse;

    public IdempotentDuplicateException(String message, Object existingResponse) {
        super(message, "DUPLICATE_REQUEST");
        this.duplicateResponse = existingResponse;
    }

    public Object getDuplicateResponse() {
        return duplicateResponse;
    }
}
