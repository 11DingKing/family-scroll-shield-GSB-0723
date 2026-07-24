package com.family.scrollshield.service;

import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.request.HeartbeatRequest;
import com.family.scrollshield.dto.response.HeartbeatResponse;
import com.family.scrollshield.exception.InvalidLeaseTokenException;
import com.family.scrollshield.repository.SessionLeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final String HEARTBEAT_SEQ_PREFIX = "lease:hbseq:";
    private static final String LEASE_CACHE_PREFIX = "lease:";

    private final SessionLeaseRepository leaseRepository;
    private final TimezoneService timezoneService;
    private final LeaseService leaseService;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.lease.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    @Value("${app.lease.grace-period-seconds:15}")
    private int gracePeriodSeconds;

    @Transactional
    public HeartbeatResponse heartbeat(HeartbeatRequest request) {
        UUID leaseToken = request.leaseToken();
        SessionLease lease = leaseRepository.findByLeaseToken(leaseToken)
                .orElseThrow(() -> new InvalidLeaseTokenException(leaseToken));

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

        if (request.sequence() != null) {
            if (!checkAndUpdateSequence(leaseToken, request.sequence())) {
                log.debug("Stale heartbeat rejected for lease {} seq={}", leaseToken, request.sequence());
                long remainingSec = Math.max(0, Duration.between(Instant.now(), lease.getExpiresAt()).getSeconds());
                return new HeartbeatResponse(
                        lease.getLeaseToken(),
                        lease.getStatus().name(),
                        lease.getExpiresAt(),
                        Instant.now(),
                        request.sequence(),
                        (int) remainingSec
                );
            }
        }

        Instant now = Instant.now();

        if (lease.getExpiresAt().isBefore(now)) {
            leaseService.expireLease(lease, now);
            return new HeartbeatResponse(
                    lease.getLeaseToken(),
                    LeaseStatus.EXPIRED.name(),
                    lease.getExpiresAt(),
                    now,
                    null,
                    0
            );
        }

        lease.setLastHeartbeatAt(now);

        Instant extendedExpiry = now.plusSeconds(heartbeatIntervalSeconds + gracePeriodSeconds);
        if (extendedExpiry.isAfter(lease.getExpiresAt()) &&
                Duration.between(lease.getStartedAt(), extendedExpiry).getSeconds() <= lease.getGrantedMinutes() * 60L) {
            lease.setExpiresAt(extendedExpiry);
        }

        leaseRepository.save(lease);

        outboxService.recordEvent("SessionLease", lease.getLeaseToken().toString(), "LEASE_HEARTBEAT",
                new HeartbeatOutboxPayload(lease.getMember().getMemberUuid(), lease.getLeaseToken(), now));

        updateLeaseCache(lease);

        long remainingSec = Math.max(0, Duration.between(now, lease.getExpiresAt()).getSeconds());

        log.debug("Heartbeat received for lease {} seq={} remaining={}s", leaseToken, request.sequence(), remainingSec);

        return new HeartbeatResponse(
                lease.getLeaseToken(),
                lease.getStatus().name(),
                lease.getExpiresAt(),
                now,
                request.sequence() != null ? request.sequence() + 1 : null,
                (int) remainingSec
        );
    }

    private boolean checkAndUpdateSequence(UUID leaseToken, long incomingSeq) {
        String key = HEARTBEAT_SEQ_PREFIX + leaseToken;
        try {
            String currentVal = redisTemplate.opsForValue().get(key);
            long currentSeq = currentVal != null ? Long.parseLong(currentVal) : -1;

            if (incomingSeq <= currentSeq) {
                return false;
            }

            redisTemplate.opsForValue().set(key, String.valueOf(incomingSeq), heartbeatIntervalSeconds * 3L, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.debug("Redis seq check failed (non-critical), accepting heartbeat: {}", e.getMessage());
            return true;
        }
    }

    private void updateLeaseCache(SessionLease lease) {
        try {
            String key = LEASE_CACHE_PREFIX + lease.getLeaseToken();
            long ttlSec = Math.max(60, Duration.between(Instant.now(), lease.getExpiresAt()).getSeconds() + 60);
            redisTemplate.opsForValue().set(key, lease.getMember().getId() + ":ACTIVE", ttlSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis cache update failed (non-critical): {}", e.getMessage());
        }
    }

    public record HeartbeatOutboxPayload(
            UUID memberId,
            UUID leaseToken,
            Instant timestamp
    ) {}
}
