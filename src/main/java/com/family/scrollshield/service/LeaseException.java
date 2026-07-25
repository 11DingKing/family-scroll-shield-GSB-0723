package com.family.scrollshield.service;

/**
 * Domain exception for lease / quota rule violations. Carries a stable code so
 * the web layer can map it to an appropriate HTTP status.
 */
public class LeaseException extends RuntimeException {

    public enum Code {
        MEMBER_NOT_FOUND,
        LEASE_NOT_FOUND,
        LEASE_NOT_ACTIVE,
        ACTIVE_LEASE_EXISTS,
        QUOTA_EXHAUSTED,
        BEDTIME_BLACKOUT,
        EXTENSION_ALREADY_USED,
        EXTENSION_NOT_ALLOWED,
        INVALID_TIME_ZONE,
        INVALID_REQUEST
    }

    private final Code code;

    public LeaseException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
