package com.family.scrollshield.exception;

import java.util.UUID;

public class LeaseNotFoundException extends ScrollShieldException {

    public LeaseNotFoundException(UUID token) {
        super("Lease not found: " + token, "LEASE_NOT_FOUND");
    }
}
