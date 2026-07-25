package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authoritative daily budget ledger for a member on a given member-local date.
 * The (member_id, plan_date) pair is unique; {@code consumed_seconds} is the
 * durable source of truth that Redis merely accelerates.
 */
@Entity
@Table(name = "viewing_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViewingPlan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    /** Member-local calendar date this plan governs. */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "daily_limit_seconds", nullable = false)
    private int dailyLimitSeconds;

    @Column(name = "consumed_seconds", nullable = false)
    private int consumedSeconds;

    @Column(name = "extension_used", nullable = false)
    private boolean extensionUsed;

    @Column(name = "extension_seconds", nullable = false)
    private int extensionSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanStatus status;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** Optimistic-lock version to guard concurrent settlement of the daily ledger. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Total time budget available today, including any approved extension. */
    public int totalBudgetSeconds() {
        return dailyLimitSeconds + extensionSeconds;
    }

    /** Remaining seconds against the (possibly extended) daily budget, never negative. */
    public int remainingSeconds() {
        return Math.max(0, totalBudgetSeconds() - consumedSeconds);
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = PlanStatus.OPEN;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
