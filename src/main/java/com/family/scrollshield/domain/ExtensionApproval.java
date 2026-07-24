package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An audit record of a bedtime-safe daily extension approval (max one 5-minute
 * grant per plan/day). The row itself is authoritative history; the granted
 * seconds are also folded into the {@link ViewingPlan}.
 */
@Entity
@Table(name = "extension_approvals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtensionApproval {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** Optional link to the lease that was active when the extension was granted. */
    @Column(name = "lease_id")
    private UUID leaseId;

    @Column(name = "approver", nullable = false)
    private String approver;

    @Column(name = "granted_seconds", nullable = false)
    private int grantedSeconds;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    /** The extension can never push viewing past this member-local bedtime-safe deadline. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
