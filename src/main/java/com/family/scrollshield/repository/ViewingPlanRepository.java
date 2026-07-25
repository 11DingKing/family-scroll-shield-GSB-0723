package com.family.scrollshield.repository;

import com.family.scrollshield.domain.ViewingPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ViewingPlanRepository extends JpaRepository<ViewingPlan, UUID> {

    Optional<ViewingPlan> findByMemberIdAndPlanDate(UUID memberId, LocalDate planDate);

    /**
     * Insert a fresh plan only if one does not already exist for the member/day. Uses
     * {@code ON CONFLICT DO NOTHING} so a concurrent creator's race does not abort the
     * caller's transaction (unlike a plain insert that violates the unique constraint).
     * Returns the number of rows inserted (1 when this caller created it, 0 otherwise).
     */
    @Modifying
    @Query(value = """
            INSERT INTO viewing_plans
                (id, member_id, plan_date, daily_limit_seconds, consumed_seconds,
                 extension_used, extension_seconds, status, version, created_at, updated_at)
            VALUES
                (:id, :memberId, :planDate, :dailyLimit, 0, false, 0, 'OPEN', 0, now(), now())
            ON CONFLICT (member_id, plan_date) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("memberId") UUID memberId,
                       @Param("planDate") LocalDate planDate,
                       @Param("dailyLimit") int dailyLimit);

    /**
     * Row-locking fetch used when settling consumption, so concurrent heartbeats
     * for the same member/day serialize on the authoritative ledger row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ViewingPlan p where p.memberId = :memberId and p.planDate = :planDate")
    Optional<ViewingPlan> lockByMemberIdAndPlanDate(@Param("memberId") UUID memberId,
                                                    @Param("planDate") LocalDate planDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ViewingPlan p where p.id = :id")
    Optional<ViewingPlan> lockById(@Param("id") UUID id);
}
