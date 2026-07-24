package com.family.scrollshield.repository;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionLeaseRepository extends JpaRepository<SessionLease, UUID> {

    Optional<SessionLease> findByLeaseToken(String leaseToken);

    /** The at-most-one ACTIVE lease for a member (guarded by a partial unique index). */
    Optional<SessionLease> findByMemberIdAndStatus(UUID memberId, LeaseStatus status);

    /**
     * Row-locking fetch of the lease so concurrent heartbeat / release / takeover
     * operations for the same lease serialize.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SessionLease l where l.leaseToken = :token")
    Optional<SessionLease> lockByLeaseToken(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SessionLease l where l.memberId = :memberId and l.status = com.family.scrollshield.domain.LeaseStatus.ACTIVE")
    Optional<SessionLease> lockActiveByMember(@Param("memberId") UUID memberId);

    @Query("select l from SessionLease l where l.status = com.family.scrollshield.domain.LeaseStatus.ACTIVE and l.expiresAt < :cutoff")
    List<SessionLease> findExpiredActive(@Param("cutoff") OffsetDateTime cutoff);
}
