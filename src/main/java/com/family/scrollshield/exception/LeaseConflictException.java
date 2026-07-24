package com.family.scrollshield.exception;

public class LeaseConflictException extends ScrollShieldException {

    public LeaseConflictException(String message) {
        super(message, "LEASE_CONFLICT");
    }
}
