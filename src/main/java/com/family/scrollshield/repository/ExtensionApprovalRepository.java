package com.family.scrollshield.repository;

import com.family.scrollshield.domain.ExtensionApproval;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExtensionApprovalRepository extends JpaRepository<ExtensionApproval, Long> {

    Optional<ExtensionApproval> findByApprovalToken(UUID token);

    Optional<ExtensionApproval> findByMemberIdAndIdempotencyKey(Long memberId, String key);

    @Query("SELECT COUNT(e) FROM ExtensionApproval e WHERE e.member.id = :memberId AND e.status IN ('APPROVED','CONSUMED') AND e.requestedAt >= :dayStart AND e.requestedAt < :dayEnd")
    long countApprovedExtensionsInWindow(@Param("memberId") Long memberId, @Param("dayStart") Instant dayStart, @Param("dayEnd") Instant dayEnd);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExtensionApproval e WHERE e.approvalToken = :token")
    Optional<ExtensionApproval> findByTokenForUpdate(@Param("token") UUID token);
}
