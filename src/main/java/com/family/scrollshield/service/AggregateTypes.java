package com.family.scrollshield.service;

/**
 * Stable aggregate-type identifiers used in the outbox for audit replay and routing.
 */
public final class AggregateTypes {
    public static final String MEMBER = "MEMBER";
    public static final String PLAN = "PLAN";
    public static final String LEASE = "LEASE";
    public static final String EXTENSION = "EXTENSION";

    private AggregateTypes() {
    }
}
