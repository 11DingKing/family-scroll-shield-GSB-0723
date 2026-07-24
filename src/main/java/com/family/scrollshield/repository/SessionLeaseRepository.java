package com.family.scrollshield.repository;

import com.family.scrollshield.domain.SessionLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionLeaseRepository extends JpaRepository<SessionLease, Long> {

    Optional<SessionLease> findByLeaseToken(UUID token);

    Optional<SessionLease> findByMemberIdAndIdempotencyKey(Long memberId, String key);

    @Query("SELECT s FROM SessionLease s WHERE s.member.id = :memberId AND s.status = 'ACTIVE'")
    Optional<SessionLease> findActiveByMemberId(@Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionLease s WHERE s.member.id = :memberId AND s.status = 'ACTIVE'")
    Optional<SessionLease> findActiveByMemberIdForUpdate(@Param("memberId") Long memberId);

    @Query("SELECT s FROM SessionLease s WHERE s.status = 'ACTIVE' AND s.expiresAt < :now")
    List<SessionLease> findExpiredActiveLeases(@Param("now") Instant now);

    @Query("SELECT s FROM SessionLease s WHERE s.status = 'ACTIVE' AND s.lastHeartbeatAt < :staleBefore AND s.expiresAt >= :now")
    List<SessionLease> findStaleActiveLeases(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
}
