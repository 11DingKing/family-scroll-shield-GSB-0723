package com.family.scrollshield.repository;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface SessionLeaseRepository extends JpaRepository<SessionLease, UUID> {

    Optional<SessionLease> findByLeaseToken(String leaseToken);

    @Query("""
            select l from SessionLease l
            where l.member.id = :memberId and l.status = :status
            """)
    Optional<SessionLease> findByMemberAndStatus(@Param("memberId") UUID memberId,
                                                 @Param("status") LeaseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("""
            select l from SessionLease l
            where l.member.id = :memberId and l.status = :status
            """)
    Optional<SessionLease> lockActiveByMember(@Param("memberId") UUID memberId,
                                              @Param("status") LeaseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("select l from SessionLease l where l.id = :id")
    Optional<SessionLease> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("select l from SessionLease l where l.leaseToken = :token")
    Optional<SessionLease> lockByToken(@Param("token") String token);

    @Query("""
            select l from SessionLease l
            where l.status = :status and l.lastHeartbeatAt < :threshold
            """)
    List<SessionLease> findExpiredActive(@Param("status") LeaseStatus status,
                                         @Param("threshold") Instant threshold);

    @Modifying
    @Query("""
            update SessionLease l
            set l.status = :newStatus, l.revision = l.revision + 1
            where l.id = :id and l.status = :expected and l.revision = :expectedRevision
            """)
    int compareAndUpdateStatus(@Param("id") UUID id,
                               @Param("expected") LeaseStatus expected,
                               @Param("expectedRevision") long expectedRevision,
                               @Param("newStatus") LeaseStatus newStatus);

    @Query("""
            select l from SessionLease l
            where l.plan.id = :planId and l.status in :statuses
            """)
    List<SessionLease> findByPlanAndStatuses(@Param("planId") UUID planId,
                                             @Param("statuses") Collection<LeaseStatus> statuses);
}
