package com.family.scrollshield.domain;

/**
 * Lifecycle status of a session lease.
 */
public enum LeaseStatus {
    /** Lease currently holds the single active slot for the member. */
    ACTIVE,
    /** Client released the lease gracefully. */
    RELEASED,
    /** Lease expired because heartbeats stopped; time was settled to the plan. */
    EXPIRED,
    /** Lease was administratively revoked. */
    REVOKED,
    /** Lease was closed because it crossed the member-local midnight boundary. */
    CROSSED_MIDNIGHT
}
