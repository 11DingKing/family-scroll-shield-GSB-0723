package com.family.scrollshield.repository;

import com.family.scrollshield.domain.SessionLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionLease s SET s.lastHeartbeatAt = :now, s.heartbeatSeq = s.heartbeatSeq + 1 " +
           "WHERE s.leaseToken = :token AND s.status = 'ACTIVE'")
    int atomicHeartbeat(@Param("token") UUID token, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionLease s SET s.lastHeartbeatAt = :now, s.heartbeatSeq = s.heartbeatSeq + 1, " +
           "s.lastClientSeq = :clientSeq " +
           "WHERE s.leaseToken = :token AND s.status = 'ACTIVE' AND s.lastClientSeq < :clientSeq AND s.heartbeatSeq = :expectedSeq")
    int atomicHeartbeatWithClientSeq(@Param("token") UUID token, @Param("now") Instant now,
                                     @Param("clientSeq") long clientSeq, @Param("expectedSeq") long expectedSeq);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionLease s SET s.lastHeartbeatAt = :now, s.heartbeatSeq = s.heartbeatSeq + 1 " +
           "WHERE s.leaseToken = :token AND s.status = 'ACTIVE' AND s.heartbeatSeq = :expectedSeq")
    int atomicHeartbeatCAS(@Param("token") UUID token, @Param("now") Instant now, @Param("expectedSeq") long expectedSeq);

    @Query("SELECT s FROM SessionLease s WHERE s.status = 'ACTIVE' AND s.expiresAt < :now")
    List<SessionLease> findExpiredActiveLeases(@Param("now") Instant now);

    @Query("SELECT s FROM SessionLease s WHERE s.status = 'ACTIVE' AND s.lastHeartbeatAt < :staleBefore AND s.expiresAt >= :now")
    List<SessionLease> findStaleActiveLeases(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
}
