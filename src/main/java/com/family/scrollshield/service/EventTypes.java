package com.family.scrollshield.service;

/**
 * Stable event-type identifiers recorded in the transactional outbox.
 */
public final class EventTypes {
    public static final String MEMBER_CREATED = "MEMBER_CREATED";
    public static final String PLAN_OPENED = "PLAN_OPENED";
    public static final String PLAN_CLOSED = "PLAN_CLOSED";
    public static final String LEASE_GRANTED = "LEASE_GRANTED";
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String LEASE_TERMINATED = "LEASE_TERMINATED";
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String LEASE_EXTENDED = "LEASE_EXTENDED";
    public static final String EXTENSION_GRANTED = "EXTENSION_GRANTED";

    private EventTypes() {
    }
}
