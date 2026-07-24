package com.family.scrollshield.exception;

public class QuotaExceededException extends ScrollShieldException {

    public QuotaExceededException(String message) {
        super(message, "QUOTA_EXCEEDED");
    }
}
