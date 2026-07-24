package com.family.scrollshield.service;

import com.family.scrollshield.domain.*;
import com.family.scrollshield.dto.request.AcquireLeaseRequest;
import com.family.scrollshield.dto.response.LeaseResponse;
import com.family.scrollshield.dto.response.ReleaseResponse;
import com.family.scrollshield.exception.*;
import com.family.scrollshield.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseService {

    private static final String LEASE_CACHE_PREFIX = "lease:";
    private static final String LEASE_MEMBER_PREFIX = "lease:member:";
    private static final String LEASE_LOCK_PREFIX = "lease:lock:";
    private static final String HEARTBEAT_SEQ_PREFIX = "lease:hbseq:";
    private static final String IDEMPOTENT_PREFIX = "idem:lease:";

    private final FamilyMemberRepository memberRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final SessionLeaseRepository leaseRepository;
    private final TimezoneService timezoneService;
    private final QuotaService quotaService;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.lease.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    @Value("${app.lease.grace-period-seconds:15}")
    private int gracePeriodSeconds;

    @Transactional
    public LeaseResponse acquireLease(AcquireLeaseRequest request) {
        FamilyMember member = memberRepository.findByMemberUuid(request.memberId())
                .orElseThrow(() -> new MemberNotFoundException(request.memberId()));

        Optional<SessionLease> existing = leaseRepository.findByMemberIdAndIdempotencyKey(member.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            SessionLease dup = existing.get();
            LeaseResponse dupResp = toLeaseResponse(dup, member);
            cacheLease(dup);
            throw new IdempotentDuplicateException("Duplicate lease request: " + request.idempotencyKey(), dupResp);
        }

        String lockKey = LEASE_LOCK_PREFIX + member.getId();
        Boolean locked = tryRedisLock(lockKey);
        try {
            if (locked == null || !locked) {
                log.warn("Redis lock unavailable for member {}, falling back to DB isolation", member.getId());
            }
            return doAcquireLease(member, request);
        } finally {
            releaseRedisLock(lockKey);
        }
    }

    private LeaseResponse doAcquireLease(FamilyMember member, AcquireLeaseRequest request) {
        Instant now = Instant.now();
        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate today = timezoneService.getLocalDate(now, zoneId);

        DailyUsage usage = getOrCreateDailyUsage(member, today, now, zoneId);

        Optional<SessionLease> activeLease = leaseRepository.findActiveByMemberIdForUpdate(member.getId());
        if (activeLease.isPresent()) {
            SessionLease active = activeLease.get();
            if (isLeaseStillValid(active, now)) {
                throw new LeaseConflictException("Member " + member.getMemberUuid() + " already has an active lease: " + active.getLeaseToken());
            }
            expireLease(active, now);
        }

        if (quotaService.isBedtimeRestricted(member, now, zoneId)) {
            throw new BedtimeWindowException("Cannot start viewing session during bedtime restriction window for teen member");
        }

        int requestedMin = request.requestedMinutes();
        int grantedMin = quotaService.calculateGrantedMinutes(member, requestedMin, usage.getRemainingMinutes(), now, zoneId);
        if (grantedMin <= 0) {
            throw new QuotaExceededException("No remaining daily quota for member " + member.getMemberUuid());
        }

        Instant expiresAt = now.plusSeconds((long) grantedMin * 60);

        SessionLease lease = SessionLease.builder()
                .leaseToken(UUID.randomUUID())
                .member(member)
                .idempotencyKey(request.idempotencyKey())
                .status(LeaseStatus.ACTIVE)
                .requestedMinutes(requestedMin)
                .grantedMinutes(grantedMin)
                .startedAt(now)
                .expiresAt(expiresAt)
                .lastHeartbeatAt(now)
                .build();

        try {
            lease = leaseRepository.save(lease);
        } catch (DataIntegrityViolationException e) {
            throw new LeaseConflictException("Concurrent lease creation detected for member " + member.getMemberUuid());
        }

        usage.setUsedMinutes(usage.getUsedMinutes() + grantedMin);
        usage.setRemainingMinutes(usage.getRemainingMinutes() - grantedMin);
        dailyUsageRepository.save(usage);

        outboxService.recordEvent("SessionLease", lease.getLeaseToken().toString(), "LEASE_ACQUIRED",
                new OutboxPayload(member.getMemberUuid(), lease.getLeaseToken(), grantedMin, now, expiresAt));

        cacheLease(lease);
        cacheIdempotentResult(request.idempotencyKey(), lease.getLeaseToken().toString());

        log.info("Lease acquired: member={} token={} granted={}min remaining={}min",
                member.getMemberUuid(), lease.getLeaseToken(), grantedMin, usage.getRemainingMinutes());

        return toLeaseResponse(lease, member);
    }

    @Transactional
    public ReleaseResponse releaseLease(UUID leaseToken, String idempotencyKey) {
        SessionLease lease = leaseRepository.findByLeaseToken(leaseToken)
                .orElseThrow(() -> new LeaseNotFoundException(leaseToken));

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            int consumed = lease.getConsumedMinutes() != null ? lease.getConsumedMinutes() : lease.getGrantedMinutes();
            DailyUsage usage = dailyUsageRepository.findByMemberIdAndUsageDate(lease.getMember().getId(),
                    timezoneService.getLocalDate(lease.getStartedAt(), lease.getMember().getTimezone())).orElse(null);
            int remaining = usage != null ? usage.getRemainingMinutes() : 0;
            return new ReleaseResponse(lease.getLeaseToken(), lease.getStatus().name(), consumed, remaining);
        }

        Instant now = Instant.now();
        ZoneId zoneId = timezoneService.getZoneId(lease.getMember().getTimezone());
        LocalDate leaseDate = timezoneService.getLocalDate(lease.getStartedAt(), zoneId);

        DailyUsage usage = dailyUsageRepository.findByMemberAndDateForUpdate(lease.getMember().getId(), leaseDate)
                .orElseGet(() -> getOrCreateDailyUsage(lease.getMember(), leaseDate, now, zoneId));

        long usedSeconds = Duration.between(lease.getStartedAt(), now).getSeconds();
        int consumedMinutes = (int) Math.ceil(usedSeconds / 60.0);
        if (consumedMinutes > lease.getGrantedMinutes()) {
            consumedMinutes = lease.getGrantedMinutes();
        }
        int unusedMinutes = lease.getGrantedMinutes() - consumedMinutes;

        if (unusedMinutes > 0) {
            usage.setRemainingMinutes(usage.getRemainingMinutes() + unusedMinutes);
            usage.setUsedMinutes(usage.getUsedMinutes() - unusedMinutes);
            dailyUsageRepository.save(usage);
        }

        lease.setStatus(LeaseStatus.RELEASED);
        lease.setEndedAt(now);
        lease.setConsumedMinutes(consumedMinutes);
        leaseRepository.save(lease);

        outboxService.recordEvent("SessionLease", lease.getLeaseToken().toString(), "LEASE_RELEASED",
                new OutboxPayload(lease.getMember().getMemberUuid(), lease.getLeaseToken(), consumedMinutes, now, lease.getExpiresAt()));

        removeLeaseCache(lease);

        log.info("Lease released: token={} consumed={}min unused={}min refunded", leaseToken, consumedMinutes, unusedMinutes);

        return new ReleaseResponse(lease.getLeaseToken(), "RELEASED", consumedMinutes, usage.getRemainingMinutes());
    }

    @Transactional(readOnly = true)
    public LeaseResponse getLease(UUID leaseToken) {
        SessionLease lease = leaseRepository.findByLeaseToken(leaseToken)
                .orElseThrow(() -> new LeaseNotFoundException(leaseToken));
        return toLeaseResponse(lease, lease.getMember());
    }

    @Transactional
    public SessionLease findAndLockActiveLease(Long memberId) {
        return leaseRepository.findActiveByMemberIdForUpdate(memberId).orElse(null);
    }

    @Transactional
    public void expireLease(SessionLease leaseRef, Instant now) {
        SessionLease lease = leaseRepository.findByLeaseToken(leaseRef.getLeaseToken()).orElse(leaseRef);

        FamilyMember member = lease.getMember();
        member.getMemberUuid();

        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate leaseDate = timezoneService.getLocalDate(lease.getStartedAt(), zoneId);

        DailyUsage usage = dailyUsageRepository.findByMemberAndDateForUpdate(member.getId(), leaseDate).orElse(null);

        long usedSeconds = Duration.between(lease.getStartedAt(), now).getSeconds();
        int consumedMinutes = (int) Math.min(lease.getGrantedMinutes(), Math.ceil(usedSeconds / 60.0));
        int unusedMinutes = lease.getGrantedMinutes() - consumedMinutes;

        if (usage != null && unusedMinutes > 0) {
            usage.setRemainingMinutes(usage.getRemainingMinutes() + unusedMinutes);
            usage.setUsedMinutes(Math.max(0, usage.getUsedMinutes() - unusedMinutes));
            dailyUsageRepository.save(usage);
        }

        lease.setStatus(LeaseStatus.EXPIRED);
        lease.setEndedAt(now);
        lease.setConsumedMinutes(consumedMinutes);
        leaseRepository.save(lease);

        outboxService.recordEvent("SessionLease", lease.getLeaseToken().toString(), "LEASE_EXPIRED",
                new OutboxPayload(member.getMemberUuid(), lease.getLeaseToken(), consumedMinutes, now, null));

        removeLeaseCache(lease);

        log.info("Lease expired: token={} consumed={}min", lease.getLeaseToken(), consumedMinutes);
    }

    @Transactional
    public void takeoverLease(SessionLease leaseRef, Instant now) {
        SessionLease lease = leaseRepository.findByLeaseToken(leaseRef.getLeaseToken()).orElse(leaseRef);
        FamilyMember member = lease.getMember();

        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate leaseDate = timezoneService.getLocalDate(lease.getStartedAt(), zoneId);

        DailyUsage usage = dailyUsageRepository.findByMemberAndDateForUpdate(member.getId(), leaseDate).orElse(null);

        long usedSeconds = Duration.between(lease.getStartedAt(), now).getSeconds();
        int consumedMinutes = (int) Math.min(lease.getGrantedMinutes(), Math.ceil(usedSeconds / 60.0));
        int unusedMinutes = lease.getGrantedMinutes() - consumedMinutes;

        if (usage != null && unusedMinutes > 0) {
            usage.setRemainingMinutes(usage.getRemainingMinutes() + unusedMinutes);
            usage.setUsedMinutes(Math.max(0, usage.getUsedMinutes() - unusedMinutes));
            dailyUsageRepository.save(usage);
        }

        lease.setStatus(LeaseStatus.TAKEN_OVER);
        lease.setEndedAt(now);
        lease.setConsumedMinutes(consumedMinutes);
        leaseRepository.save(lease);

        outboxService.recordEvent("SessionLease", lease.getLeaseToken().toString(), "LEASE_TAKEN_OVER",
                new OutboxPayload(member.getMemberUuid(), lease.getLeaseToken(), consumedMinutes, now, null));

        removeLeaseCache(lease);

        log.warn("Lease taken over (stale heartbeat): token={} consumed={}min", lease.getLeaseToken(), consumedMinutes);
    }

    private DailyUsage getOrCreateDailyUsage(FamilyMember member, LocalDate date, Instant now, ZoneId zoneId) {
        Optional<DailyUsage> existing = dailyUsageRepository.findByMemberAndDateForUpdate(member.getId(), date);
        if (existing.isPresent()) {
            return existing.get();
        }

        int dailyLimit = quotaService.getDailyLimit(member);
        DailyUsage usage = DailyUsage.builder()
                .member(member)
                .usageDate(date)
                .dailyLimitMin(dailyLimit)
                .usedMinutes(0)
                .remainingMinutes(dailyLimit)
                .extensionsUsed(0)
                .build();
        return dailyUsageRepository.save(usage);
    }

    private boolean isLeaseStillValid(SessionLease lease, Instant now) {
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            return false;
        }
        Instant staleThreshold = now.minusSeconds(heartbeatIntervalSeconds + gracePeriodSeconds);
        boolean notExpired = lease.getExpiresAt().isAfter(now);
        boolean heartbeatFresh = lease.getLastHeartbeatAt().isAfter(staleThreshold);
        return notExpired && heartbeatFresh;
    }

    LeaseResponse toLeaseResponse(SessionLease lease, FamilyMember member) {
        Instant now = Instant.now();
        long remainingSessionSec = Math.max(0, Duration.between(now, lease.getExpiresAt()).getSeconds());
        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate today = timezoneService.getLocalDate(now, zoneId);

        int remainingDaily;
        remainingDaily = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), today)
                .map(DailyUsage::getRemainingMinutes).orElse(quotaService.getDailyLimit(member));

        return new LeaseResponse(
                lease.getLeaseToken(),
                member.getMemberUuid(),
                lease.getStatus().name(),
                lease.getGrantedMinutes(),
                lease.getStartedAt(),
                lease.getExpiresAt(),
                remainingDaily,
                (int) remainingSessionSec
        );
    }

    private void cacheLease(SessionLease lease) {
        try {
            String key = LEASE_CACHE_PREFIX + lease.getLeaseToken();
            long ttlSec = Math.max(60, Duration.between(Instant.now(), lease.getExpiresAt()).getSeconds() + 60);
            redisTemplate.opsForValue().set(key, lease.getMember().getId() + ":" + lease.getStatus().name(), ttlSec, TimeUnit.SECONDS);

            String memberKey = LEASE_MEMBER_PREFIX + lease.getMember().getId();
            redisTemplate.opsForValue().set(memberKey, lease.getLeaseToken().toString(), ttlSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis cache write failed (non-critical): {}", e.getMessage());
        }
    }

    private void removeLeaseCache(SessionLease lease) {
        try {
            redisTemplate.delete(LEASE_CACHE_PREFIX + lease.getLeaseToken());
            redisTemplate.delete(LEASE_MEMBER_PREFIX + lease.getMember().getId());
            redisTemplate.delete(HEARTBEAT_SEQ_PREFIX + lease.getLeaseToken());
        } catch (Exception e) {
            log.debug("Redis cache delete failed (non-critical): {}", e.getMessage());
        }
    }

    private void cacheIdempotentResult(String idempotencyKey, String leaseToken) {
        try {
            redisTemplate.opsForValue().set(IDEMPOTENT_PREFIX + idempotencyKey, leaseToken, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("Redis idempotent cache failed (non-critical): {}", e.getMessage());
        }
    }

    private Boolean tryRedisLock(String key) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, "locked", 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis lock failed (will rely on DB): {}", e.getMessage());
            return false;
        }
    }

    private void releaseRedisLock(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("Redis lock release failed (non-critical): {}", e.getMessage());
        }
    }

    public record OutboxPayload(
            UUID memberId,
            UUID leaseToken,
            Integer minutes,
            Instant timestamp,
            Instant expiresAt
    ) {}
}
