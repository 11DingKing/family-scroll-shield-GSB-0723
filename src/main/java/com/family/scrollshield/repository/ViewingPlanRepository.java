package com.family.scrollshield.repository;

import com.family.scrollshield.domain.ViewingPlan;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ViewingPlanRepository extends JpaRepository<ViewingPlan, UUID> {

    Optional<ViewingPlan> findByMemberIdAndPlanDate(UUID memberId, LocalDate planDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("select p from ViewingPlan p where p.id = :id")
    Optional<ViewingPlan> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("select p from ViewingPlan p where p.member.id = :memberId and p.planDate = :planDate")
    Optional<ViewingPlan> lockByMemberAndDate(@Param("memberId") UUID memberId,
                                              @Param("planDate") LocalDate planDate);

    @Query("""
            select p from ViewingPlan p
            where p.member.id = :memberId and p.planDate < :planDate and p.status = com.family.scrollshield.domain.PlanStatus.OPEN
            order by p.planDate asc
            """)
    java.util.List<ViewingPlan> findOpenPlansBefore(@Param("memberId") UUID memberId,
                                                    @Param("planDate") LocalDate planDate);
}
