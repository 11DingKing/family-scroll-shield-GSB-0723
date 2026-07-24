package com.family.scrollshield.service;

import com.family.scrollshield.domain.DailyUsage;
import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.repository.DailyUsageRepository;
import com.family.scrollshield.repository.FamilyMemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseRecoveryService {

    private static final String LEASE_MEMBER_PREFIX = "lease:member:";
    private static final String LEASE_CACHE_PREFIX = "lease:";

    private final SessionLeaseRepository leaseRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final FamilyMemberRepository memberRepository;
    private final LeaseService leaseService;
    private final TimezoneService timezoneService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.lease.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    @Value("${app.lease.grace-period-seconds:15}")
    private int gracePeriodSeconds;

    @Scheduled(fixedDelayString = "${app.lease.heartbeat-interval-seconds:30}000")
    @Transactional
    public void recoverExpiredLeases() {
        Instant now = Instant.now();

        List<SessionLease> expired = leaseRepository.findExpiredActiveLeases(now);
        for (SessionLease lease : expired) {
            try {
                log.info("Recovering expired lease: {}", lease.getLeaseToken());
                leaseService.expireLease(lease, now);
            } catch (Exception e) {
                log.error("Error recovering expired lease {}: {}", lease.getLeaseToken(), e.getMessage(), e);
            }
        }

        Instant staleBefore = now.minusSeconds(heartbeatIntervalSeconds + gracePeriodSeconds);
        List<SessionLease> stale = leaseRepository.findStaleActiveLeases(staleBefore, now);
        for (SessionLease lease : stale) {
            try {
                log.info("Taking over stale lease: {} lastHb={} seq={}",
                        lease.getLeaseToken(), lease.getLastHeartbeatAt(), lease.getHeartbeatSeq());
                leaseService.takeoverLease(lease, now);
            } catch (Exception e) {
                log.error("Error taking over stale lease {}: {}", lease.getLeaseToken(), e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void settleCrossMidnightLeases() {
        Instant now = Instant.now();
        List<FamilyMember> members = memberRepository.findAll();

        for (FamilyMember member : members) {
            try {
                settleMemberIfCrossedMidnight(member, now);
            } catch (Exception e) {
                log.error("Error in midnight settlement for member {}: {}", member.getMemberUuid(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void settleMemberIfCrossedMidnight(FamilyMember member, Instant now) {
        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate today = timezoneService.getLocalDate(now, zoneId);
        Instant localMidnightToday = today.atStartOfDay(zoneId).toInstant();

        SessionLease activeLease = leaseRepository.findActiveByMemberIdForUpdate(member.getId()).orElse(null);
        if (activeLease == null) {
            return;
        }

        if (activeLease.getStartedAt().isBefore(localMidnightToday)) {
            LocalDate leaseStartDate = timezoneService.getLocalDate(activeLease.getStartedAt(), zoneId);

            log.info("Cross-midnight settlement: member={} lease started on {} (now {}) in timezone {}",
                    member.getMemberUuid(), leaseStartDate, today, member.getTimezone());

            leaseService.expireLease(activeLease, localMidnightToday);

            DailyUsage yesterdayUsage = dailyUsageRepository
                    .findByMemberIdAndUsageDate(member.getId(), leaseStartDate).orElse(null);
            if (yesterdayUsage != null) {
                log.info("Settled usage for member {} on {}: used={}min remaining={}min extensions={}",
                        member.getMemberUuid(), leaseStartDate,
                        yesterdayUsage.getUsedMinutes(), yesterdayUsage.getRemainingMinutes(),
                        yesterdayUsage.getExtensionsUsed());
            }
        }
    }

    public void reconcileLeaseCache(Long memberId) {
        try {
            var activeLease = leaseRepository.findActiveByMemberId(memberId);
            String memberKey = LEASE_MEMBER_PREFIX + memberId;
            if (activeLease.isPresent()) {
                SessionLease lease = activeLease.get();
                long ttlSec = Math.max(60, Duration.between(Instant.now(), lease.getExpiresAt()).getSeconds() + 60);
                redisTemplate.opsForValue().set(memberKey, lease.getLeaseToken().toString(), ttlSec, TimeUnit.SECONDS);
                redisTemplate.opsForValue().set(LEASE_CACHE_PREFIX + lease.getLeaseToken(),
                        memberId + ":ACTIVE", ttlSec, TimeUnit.SECONDS);
                log.debug("Reconciled lease cache for member {} from DB (token={})", memberId, lease.getLeaseToken());
            } else {
                redisTemplate.delete(memberKey);
                log.debug("Cleared lease cache for member {} (no active lease in DB)", memberId);
            }
        } catch (Exception e) {
            log.warn("Cache reconciliation failed for member {} (non-critical): {}", memberId, e.getMessage());
        }
    }
}
