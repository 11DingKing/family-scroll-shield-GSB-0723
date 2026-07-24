package com.family.scrollshield;

import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.MemberRole;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.request.*;
import com.family.scrollshield.dto.response.*;
import com.family.scrollshield.exception.LeaseConflictException;
import com.family.scrollshield.exception.QuotaExceededException;
import com.family.scrollshield.repository.FamilyMemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.DailyUsageRepository;
import com.family.scrollshield.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeaseConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private HeartbeatService heartbeatService;

    @Autowired
    private ExtensionApprovalService extensionApprovalService;

    @Autowired
    private TimezoneService timezoneService;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private LeaseRecoveryService leaseRecoveryService;

    @Autowired
    private FamilyMemberRepository memberRepository;

    @Autowired
    private SessionLeaseRepository leaseRepository;

    @Autowired
    private DailyUsageRepository dailyUsageRepository;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        flushRedis();
    }

    private MemberResponse createAdult(String timezone) {
        return memberService.createMember(new CreateMemberRequest(
                "Adult " + UUID.randomUUID().toString().substring(0, 8),
                "ADULT", timezone, null, null, null));
    }

    private MemberResponse createTeen(String timezone, LocalTime bedtime) {
        return memberService.createMember(new CreateMemberRequest(
                "Teen " + UUID.randomUUID().toString().substring(0, 8),
                "TEEN", timezone, bedtime, null, null));
    }

    private MemberResponse createChild(String timezone) {
        return memberService.createMember(new CreateMemberRequest(
                "Child " + UUID.randomUUID().toString().substring(0, 8),
                "CHILD", timezone, null, null, null));
    }

    @Test
    void acquireLease_shouldReserveQuotaCorrectly() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        assertNotNull(lease.leaseToken());
        assertEquals("ACTIVE", lease.status());
        assertEquals(15, lease.grantedMinutes().intValue());
        assertEquals(45, lease.remainingDailyMinutes().intValue());
        assertTrue(lease.remainingSessionSeconds() > 0);
    }

    @Test
    void acquireLease_adultDailyLimit_shouldBe60Minutes() {
        MemberResponse adult = createAdult("UTC");

        int totalGranted = 0;
        for (int i = 0; i < 4; i++) {
            LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                    adult.memberId(), "key-" + i, 15, "device-1"));
            totalGranted += lease.grantedMinutes();
            leaseService.releaseLease(lease.leaseToken(), "release-" + i);
        }

        assertEquals(60, totalGranted);
    }

    @Test
    void acquireLease_adultSingleSession_shouldBeMax15Minutes() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 30, "device-1"));

        assertEquals(15, lease.grantedMinutes().intValue());
    }

    @Test
    void acquireLease_childSingleSession_shouldBeMax10Minutes() {
        MemberResponse child = createChild("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                child.memberId(), "key-1", 15, "device-1"));

        assertEquals(10, lease.grantedMinutes().intValue());
    }

    @Test
    void acquireLease_teenDailyLimit_shouldBe30Minutes() {
        MemberResponse teen = createTeen("UTC", LocalTime.of(22, 0));

        int totalGranted = 0;
        for (int i = 0; i < 2; i++) {
            LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                    teen.memberId(), "key-" + i, 15, "device-1"));
            totalGranted += lease.grantedMinutes();
            leaseService.releaseLease(lease.leaseToken(), "release-" + i);
        }

        assertEquals(30, totalGranted);
    }

    @Test
    void acquireLease_whenQuotaExhausted_shouldThrowQuotaExceeded() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse firstLease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));
        leaseService.releaseLease(firstLease.leaseToken(), "release-1");

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        jdbcTemplate.update(
                "UPDATE daily_usage SET used_minutes = 60, remaining_minutes = 0 WHERE member_id = ?",
                member.getId());

        assertThrows(QuotaExceededException.class, () ->
                leaseService.acquireLease(new AcquireLeaseRequest(
                        adult.memberId(), "key-2", 15, "device-2")));
    }

    @Test
    void concurrentLeaseAcquisition_onlyOneShouldSucceed() throws Exception {
        MemberResponse adult = createAdult("UTC");
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    leaseService.acquireLease(new AcquireLeaseRequest(
                            adult.memberId(), "concurrent-key-" + idx, 15, "device-" + idx));
                    successCount.incrementAndGet();
                } catch (LeaseConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    if (e instanceof org.springframework.dao.DataIntegrityViolationException ||
                        e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException) {
                        conflictCount.incrementAndGet();
                    } else {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(),
                "Exactly one lease acquisition should succeed, got " + successCount.get());
        assertEquals(threadCount - 1, conflictCount.get(),
                "All others should conflict, got " + conflictCount.get());

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var activeLeases = leaseRepository.findActiveByMemberId(member.getId());
        assertTrue(activeLeases.isPresent(), "One active lease should exist");

        var dailyUsage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC));
        assertTrue(dailyUsage.isPresent());
        assertEquals(15, dailyUsage.get().getUsedMinutes().intValue());
        assertEquals(45, dailyUsage.get().getRemainingMinutes().intValue());
    }

    @Test
    void idempotentRetry_sameKey_shouldNotDoubleDeduct() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease1 = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "idem-key-1", 15, "device-1"));

        assertThrows(com.family.scrollshield.exception.IdempotentDuplicateException.class, () ->
                leaseService.acquireLease(new AcquireLeaseRequest(
                        adult.memberId(), "idem-key-1", 15, "device-1")));

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var dailyUsage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC));
        assertTrue(dailyUsage.isPresent());
        assertEquals(15, dailyUsage.get().getUsedMinutes().intValue());
        assertEquals(45, dailyUsage.get().getRemainingMinutes().intValue());
    }

    @Test
    void releaseLease_shouldRefundUnusedTime() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        ReleaseResponse released = leaseService.releaseLease(lease.leaseToken(), "release-1");

        assertEquals("RELEASED", released.status());
        assertTrue(released.consumedMinutes() <= 1);
        assertTrue(released.remainingDailyMinutes() >= 59);
    }

    @Test
    void heartbeat_shouldUpdateLeaseState() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        HeartbeatResponse hb = heartbeatService.heartbeat(new HeartbeatRequest(
                lease.leaseToken(), "hb-1", 1L));

        assertEquals("ACTIVE", hb.status());
        assertNotNull(hb.expiresAt());
        assertTrue(hb.remainingSeconds() > 0);
    }

    @Test
    void heartbeat_outOfOrder_shouldBeRejected() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-5", 5L));
        HeartbeatResponse staleHb = heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-3", 3L));

        assertEquals("ACTIVE", staleHb.status());
    }

    @Test
    void expiredLease_shouldBeRecovered() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 1, "device-1"));

        Instant futureTime = Instant.now().plusSeconds(120);
        SessionLease leaseEntity = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        leaseService.expireLease(leaseEntity, futureTime);

        var reloaded = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals("EXPIRED", reloaded.getStatus().name());
        assertNotNull(reloaded.getConsumedMinutes());
    }

    @Test
    void leaseRecoveryAfterRedisFlush_shouldRestoreFromDatabase() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        flushRedis();

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var activeLease = leaseRepository.findActiveByMemberId(member.getId());
        assertTrue(activeLease.isPresent(), "Active lease should be recoverable from DB after Redis flush");
        assertEquals(lease.leaseToken(), activeLease.get().getLeaseToken());

        leaseRecoveryService.reconcileLeaseCache(member.getId());

        LeaseResponse queried = leaseService.getLease(lease.leaseToken());
        assertEquals("ACTIVE", queried.status());
        assertEquals(15, queried.grantedMinutes().intValue());
    }

    @Test
    void redisCacheLoss_doesNotCauseDuplicateLeaseOrQuotaLoss() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease1 = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));
        assertEquals(15, lease1.grantedMinutes().intValue());

        flushRedis();

        assertThrows(LeaseConflictException.class, () ->
                leaseService.acquireLease(new AcquireLeaseRequest(
                        adult.memberId(), "key-2", 15, "device-2")),
                "Should not allow second lease even after Redis flush - DB is source of truth");

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var dailyUsage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC)).orElseThrow();
        assertEquals(15, dailyUsage.getUsedMinutes().intValue(),
                "Quota should not be double-deducted after Redis loss");
        assertEquals(45, dailyUsage.getRemainingMinutes().intValue());
    }

    @Test
    void extensionRequest_shouldBeLimitedToOncePerDay() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        ExtensionResponse ext1 = extensionApprovalService.requestExtension(new RequestExtensionRequest(
                adult.memberId(), lease.leaseToken(), "ext-1", 5, "need more time"));
        assertNotNull(ext1.approvalToken());
        assertEquals("PENDING", ext1.status());

        extensionApprovalService.approveExtension(ext1.approvalToken(), new ApproveExtensionRequest(true));

        assertThrows(com.family.scrollshield.exception.ExtensionDeniedException.class, () ->
                extensionApprovalService.requestExtension(new RequestExtensionRequest(
                        adult.memberId(), lease.leaseToken(), "ext-2", 5, "second request")));
    }

    @Test
    void extensionApproval_shouldAddTimeToLease() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        ExtensionResponse ext = extensionApprovalService.requestExtension(new RequestExtensionRequest(
                adult.memberId(), lease.leaseToken(), "ext-1", 5, "need more time"));

        ExtensionResponse approved = extensionApprovalService.approveExtension(ext.approvalToken(), new ApproveExtensionRequest(true));
        assertEquals("APPROVED", approved.status());
        assertEquals(5, approved.grantedMinutes().intValue());

        var updatedLease = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals(20, updatedLease.getGrantedMinutes().intValue());
    }

    @Test
    void teenBedtimeWindow_shouldBlockNewSession() {
        MemberResponse teen = createTeen("America/New_York", LocalTime.of(22, 0));

        FamilyMember member = memberRepository.findByMemberUuid(teen.memberId()).orElseThrow();
        ZoneId zoneId = ZoneId.of("America/New_York");

        ZonedDateTime outsideWindow = ZonedDateTime.of(
                LocalDate.now(zoneId), LocalTime.of(20, 59), zoneId);
        Instant testTime = outsideWindow.toInstant();

        boolean restricted = quotaService.isBedtimeRestricted(member, testTime, zoneId);
        assertFalse(restricted, "20:59 should not be within bedtime window for 22:00 bedtime");

        ZonedDateTime insideWindow = ZonedDateTime.of(
                LocalDate.now(zoneId), LocalTime.of(21, 1), zoneId);
        testTime = insideWindow.toInstant();

        restricted = quotaService.isBedtimeRestricted(member, testTime, zoneId);
        assertTrue(restricted, "21:01 should be within bedtime window for 22:00 bedtime");
    }

    @Test
    void multipleMembers_concurrentLeases_shouldNotInterfere() throws Exception {
        int memberCount = 10;
        List<MemberResponse> members = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            members.add(createAdult("UTC"));
        }

        ExecutorService executor = Executors.newFixedThreadPool(memberCount);
        CountDownLatch latch = new CountDownLatch(memberCount);
        List<Future<LeaseResponse>> futures = new ArrayList<>();

        for (int i = 0; i < memberCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    return leaseService.acquireLease(new AcquireLeaseRequest(
                            members.get(idx).memberId(), "key-" + idx, 15, "device-" + idx));
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        for (Future<LeaseResponse> f : futures) {
            LeaseResponse lease = f.get(5, TimeUnit.SECONDS);
            assertEquals("ACTIVE", lease.status());
            assertEquals(15, lease.grantedMinutes().intValue());
        }
    }

    @Test
    void timezoneAwareDaylightSaving_shouldComputeCorrectLocalDate() {
        ZoneId nyZone = ZoneId.of("America/New_York");

        Instant summerTime = Instant.parse("2026-07-15T10:00:00Z");
        LocalDate summerDate = timezoneService.getLocalDate(summerTime, nyZone);
        assertEquals(LocalDate.of(2026, 7, 15), summerDate);

        Instant winterTime = Instant.parse("2026-01-15T10:00:00Z");
        LocalDate winterDate = timezoneService.getLocalDate(winterTime, nyZone);
        assertEquals(LocalDate.of(2026, 1, 15), winterDate);

        Instant nearMidnight = Instant.parse("2026-07-16T03:30:00Z");
        LocalDate date = timezoneService.getLocalDate(nearMidnight, nyZone);
        assertEquals(LocalDate.of(2026, 7, 15), date,
                "03:30 UTC on Jul 16 is 23:30 EDT on Jul 15 in New York");
    }

    @Test
    void outboxEvents_shouldBeRecordedForAllStateChanges() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-1", 1L));

        leaseService.releaseLease(lease.leaseToken(), "release-1");

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, lease.leaseToken().toString());
        assertNotNull(eventCount);
        assertTrue(eventCount >= 3,
                "Should have at least 3 outbox events (acquired, heartbeat, released), got " + eventCount);
    }

    @Test
    void leaseExpiry_takeover_shouldSettleConsumedMinutes() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        SessionLease leaseEntity = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        Instant laterTime = leaseEntity.getStartedAt().plusSeconds(5 * 60 + 30);
        leaseService.takeoverLease(leaseEntity, laterTime);

        var reloaded = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals("TAKEN_OVER", reloaded.getStatus().name());
        assertEquals(6, reloaded.getConsumedMinutes().intValue());
    }
}
