package com.family.scrollshield.service;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.request.HeartbeatRequest;
import com.family.scrollshield.dto.response.HeartbeatResponse;
import com.family.scrollshield.exception.LeaseNotFoundException;
import com.family.scrollshield.repository.SessionLeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final String HEARTBEAT_SEQ_PREFIX = "lease:hbseq:";

    private final SessionLeaseRepository leaseRepository;
    private final OutboxService outboxService;
    private final LeaseService leaseService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.lease.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    @Value("${app.lease.grace-period-seconds:15}")
    private int gracePeriodSeconds;

    @Transactional
    public HeartbeatResponse heartbeat(HeartbeatRequest request) {
        UUID leaseToken = request.leaseToken();

        SessionLease lease = leaseRepository.findByLeaseToken(leaseToken)
                .orElseThrow(() -> new LeaseNotFoundException(leaseToken));

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            return new HeartbeatResponse(
                    lease.getLeaseToken(),
                    lease.getStatus().name(),
                    lease.getExpiresAt(),
                    Instant.now(),
                    null,
                    0
            );
        }

        UUID memberUuid = lease.getMember().getMemberUuid();
        Instant now = Instant.now();

        if (lease.getExpiresAt().isBefore(now)) {
            leaseService.expireLease(lease, now);
            SessionLease reloaded = leaseRepository.findByLeaseToken(leaseToken).orElseThrow();
            return new HeartbeatResponse(
                    reloaded.getLeaseToken(),
                    reloaded.getStatus().name(),
                    reloaded.getExpiresAt(),
                    now,
                    null,
                    0
            );
        }

        long expectedServerSeq = lease.getHeartbeatSeq();
        long lastClientSeq = lease.getLastClientSeq();

        if (request.sequence() != null) {
            long incomingClientSeq = request.sequence();

            if (incomingClientSeq <= lastClientSeq) {
                log.debug("Stale heartbeat rejected for lease {} clientSeq={} lastClientSeq={}",
                        leaseToken, incomingClientSeq, lastClientSeq);
                long remainingSec = Math.max(0, Duration.between(now, lease.getExpiresAt()).getSeconds());
                return new HeartbeatResponse(
                        lease.getLeaseToken(),
                        lease.getStatus().name(),
                        lease.getExpiresAt(),
                        now,
                        incomingClientSeq,
                        (int) remainingSec
                );
            }

            int updated = leaseRepository.atomicHeartbeatWithClientSeq(
                    leaseToken, now, incomingClientSeq, expectedServerSeq);

            if (updated == 0) {
                log.debug("Concurrent heartbeat lost race for lease {} (serverSeq={})", leaseToken, expectedServerSeq);
                SessionLease fresh = leaseRepository.findByLeaseToken(leaseToken).orElseThrow();
                long remainingSec = Math.max(0, Duration.between(now, fresh.getExpiresAt()).getSeconds());
                return new HeartbeatResponse(
                        fresh.getLeaseToken(),
                        fresh.getStatus().name(),
                        fresh.getExpiresAt(),
                        now,
                        fresh.getHeartbeatSeq(),
                        (int) remainingSec
                );
            }
        } else {
            int updated = leaseRepository.atomicHeartbeat(leaseToken, now);
            if (updated == 0) {
                SessionLease fresh = leaseRepository.findByLeaseToken(leaseToken).orElseThrow();
                long remainingSec = Math.max(0, Duration.between(now, fresh.getExpiresAt()).getSeconds());
                return new HeartbeatResponse(
                        fresh.getLeaseToken(),
                        fresh.getStatus().name(),
                        fresh.getExpiresAt(),
                        now,
                        null,
                        (int) remainingSec
                );
            }
        }

        SessionLease updatedLease = leaseRepository.findByLeaseToken(leaseToken).orElseThrow();

        outboxService.recordEvent("SessionLease", leaseToken.toString(), "LEASE_HEARTBEAT",
                new HeartbeatOutboxPayload(memberUuid, leaseToken, now));

        updateRedisSeqCache(leaseToken, updatedLease.getHeartbeatSeq());

        long remainingSec = Math.max(0, Duration.between(now, updatedLease.getExpiresAt()).getSeconds());

        log.debug("Heartbeat accepted for lease {} serverSeq={} clientSeq={} remaining={}s",
                leaseToken, updatedLease.getHeartbeatSeq(), request.sequence(), remainingSec);

        return new HeartbeatResponse(
                updatedLease.getLeaseToken(),
                updatedLease.getStatus().name(),
                updatedLease.getExpiresAt(),
                now,
                updatedLease.getHeartbeatSeq(),
                (int) remainingSec
        );
    }

    private void updateRedisSeqCache(UUID leaseToken, long seq) {
        try {
            String key = HEARTBEAT_SEQ_PREFIX + leaseToken;
            redisTemplate.opsForValue().set(key, String.valueOf(seq),
                    heartbeatIntervalSeconds * 3L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis seq cache update failed (non-critical, DB is authoritative): {}", e.getMessage());
        }
    }

    public record HeartbeatOutboxPayload(
            UUID memberId,
            UUID leaseToken,
            Instant timestamp
    ) {}
}
