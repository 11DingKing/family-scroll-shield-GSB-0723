package com.family.scrollshield.exception;

public abstract class ScrollShieldException extends RuntimeException {

    private final String errorCode;

    protected ScrollShieldException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ScrollShieldException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
