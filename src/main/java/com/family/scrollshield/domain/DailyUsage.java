package com.family.scrollshield.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "usage_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "daily_limit_min")
    private Integer dailyLimitMin;

    @Column(name = "used_minutes")
    private Integer usedMinutes;

    @Column(name = "remaining_minutes")
    private Integer remainingMinutes;

    @Column(name = "extensions_used")
    @Builder.Default
    private Integer extensionsUsed = 0;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
