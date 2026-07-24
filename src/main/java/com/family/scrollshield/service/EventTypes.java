package com.family.scrollshield.service;

public final class EventTypes {
    public static final String MEMBER_CREATED = "MemberCreated";
    public static final String PLAN_OPENED = "PlanOpened";
    public static final String PLAN_CLOSED = "PlanClosed";
    public static final String LEASE_GRANTED = "LeaseGranted";
    public static final String LEASE_HEARTBEAT = "LeaseHeartbeat";
    public static final String LEASE_EXTENDED = "LeaseExtended";
    public static final String LEASE_RELEASED = "LeaseReleased";
    public static final String LEASE_EXPIRED = "LeaseExpired";
    public static final String LEASE_REVOKED = "LeaseRevoked";
    public static final String LEASE_CROSSED_MIDNIGHT = "LeaseCrossedMidnight";
    public static final String EXTENSION_GRANTED = "ExtensionGranted";

    private EventTypes() {}
}
