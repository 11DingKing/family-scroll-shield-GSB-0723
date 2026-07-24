package com.family.scrollshield.exception;

import java.util.UUID;

public class InvalidLeaseTokenException extends ScrollShieldException {

    public InvalidLeaseTokenException(UUID token) {
        super("Invalid or stale lease token: " + token, "INVALID_LEASE_TOKEN");
    }
}
