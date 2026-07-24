package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "viewing_plans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewingPlan {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

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
    @Column(nullable = false, length = 16)
    private PlanStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = PlanStatus.OPEN;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public int totalBudgetSeconds() {
        return dailyLimitSeconds + extensionSeconds;
    }

    public int remainingSeconds() {
        return Math.max(0, totalBudgetSeconds() - consumedSeconds);
    }
}
