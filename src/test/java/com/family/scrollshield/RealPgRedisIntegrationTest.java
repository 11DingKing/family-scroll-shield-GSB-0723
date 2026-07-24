package com.family.scrollshield;

import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.request.*;
import com.family.scrollshield.dto.response.*;
import com.family.scrollshield.exception.LeaseConflictException;
import com.family.scrollshield.repository.FamilyMemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.DailyUsageRepository;
import com.family.scrollshield.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RealPgRedisIntegrationTest extends AbstractIntegrationTestIT {

    @Autowired private LeaseService leaseService;
    @Autowired private MemberService memberService;
    @Autowired private HeartbeatService heartbeatService;
    @Autowired private ExtensionApprovalService extensionApprovalService;
    @Autowired private LeaseRecoveryService leaseRecoveryService;
    @Autowired private OutboxService outboxService;
    @Autowired private FamilyMemberRepository memberRepository;
    @Autowired private SessionLeaseRepository leaseRepository;
    @Autowired private DailyUsageRepository dailyUsageRepository;
    @Autowired private TestWebhookController webhookController;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        webhookController.clear();
        outboxService.setWebhookUrl("http://localhost:" + port + "/test-webhook");
    }

    private MemberResponse createAdult(String timezone) {
        return memberService.createMember(new CreateMemberRequest(
                "Adult-" + UUID.randomUUID().toString().substring(0, 6), "ADULT", timezone, null, null, null));
    }

    private MemberResponse createTeen(String timezone, LocalTime bedtime) {
        return memberService.createMember(new CreateMemberRequest(
                "Teen-" + UUID.randomUUID().toString().substring(0, 6), "TEEN", timezone, bedtime, null, null));
    }

    @Test
    void concurrentLeaseAcquisition_sameMember_onlyOneSucceeds_realPostgres() throws Exception {
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
                } catch (LeaseConflictException | org.springframework.dao.DataIntegrityViolationException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, successCount.get(),
                "Exactly one lease should succeed on real PostgreSQL, got " + successCount.get());
        assertEquals(threadCount - 1, conflictCount.get(),
                "All others should conflict, got " + conflictCount.get());

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var activeLease = leaseRepository.findActiveByMemberId(member.getId());
        assertTrue(activeLease.isPresent());
        assertEquals(LeaseStatus.ACTIVE, activeLease.get().getStatus());

        var dailyUsage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC)).orElseThrow();
        assertEquals(15, dailyUsage.getUsedMinutes().intValue());
        assertEquals(45, dailyUsage.getRemainingMinutes().intValue());
    }

    @Test
    void redisFlush_doesNotAllowDuplicateLease_realPostgresRedis() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));
        assertNotNull(lease.leaseToken());

        flushRedis();

        assertThrows(LeaseConflictException.class, () ->
                leaseService.acquireLease(new AcquireLeaseRequest(
                        adult.memberId(), "key-2", 15, "device-2")),
                "After Redis flush, DB partial unique index still prevents duplicate leases");

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var usage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC)).orElseThrow();
        assertEquals(15, usage.getUsedMinutes().intValue());
        assertEquals(45, usage.getRemainingMinutes().intValue());
    }

    @Test
    void redisFlush_staleHeartbeatStillRejected_realPostgres() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-10", 10L));

        SessionLease afterHb = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals(1L, afterHb.getHeartbeatSeq().longValue());
        assertEquals(10L, afterHb.getLastClientSeq().longValue());

        flushRedis();

        HeartbeatResponse staleHb = heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-5", 5L));
        assertEquals("ACTIVE", staleHb.status());

        SessionLease afterStale = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals(1L, afterStale.getHeartbeatSeq().longValue(),
                "Stale heartbeat (seq=5 < lastClientSeq=10) must not advance DB state even after Redis flush");
        assertEquals(10L, afterStale.getLastClientSeq().longValue());

        HeartbeatResponse newHb = heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-15", 15L));
        assertEquals("ACTIVE", newHb.status());

        SessionLease afterNew = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals(2L, afterNew.getHeartbeatSeq().longValue(),
                "New higher client seq after Redis flush should be accepted");
        assertEquals(15L, afterNew.getLastClientSeq().longValue());
    }

    @Test
    void concurrentHeartbeats_sameClientSeq_onlyOneAccepted_realPostgres() throws Exception {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        SessionLease initial = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        long startingSeq = initial.getHeartbeatSeq();

        int threadCount = 15;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    heartbeatService.heartbeat(new HeartbeatRequest(lease.leaseToken(), "hb-race", 100L));
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        executor.shutdown();

        SessionLease after = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertEquals(startingSeq + 1, after.getHeartbeatSeq().longValue(),
                "With same client seq, PostgreSQL CAS ensures only one heartbeat advances server seq");
        assertEquals(100L, after.getLastClientSeq().longValue());
    }

    @Test
    void instanceRestart_stateRecoveredFromDb_realPostgres() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));
        UUID leaseToken = lease.leaseToken();

        heartbeatService.heartbeat(new HeartbeatRequest(leaseToken, "hb-1", 5L));

        flushRedis();

        LeaseResponse queried = leaseService.getLease(leaseToken);
        assertEquals("ACTIVE", queried.status());
        assertEquals(15, queried.grantedMinutes().intValue());

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var activeLease = leaseRepository.findActiveByMemberId(member.getId());
        assertTrue(activeLease.isPresent(),
                "After simulated restart (Redis flush + fresh service query), state is recovered from PostgreSQL");
        assertEquals(leaseToken, activeLease.get().getLeaseToken());

        leaseRecoveryService.reconcileLeaseCache(member.getId());
    }

    @Test
    void timezoneCrossMidnight_settleLease_realPostgres() {
        MemberResponse tokyoAdult = memberService.createMember(new CreateMemberRequest(
                "Tokyo Adult", "ADULT", "Asia/Tokyo", null, null, null));

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                tokyoAdult.memberId(), "key-1", 15, "device-1"));

        FamilyMember member = memberRepository.findByMemberUuid(tokyoAdult.memberId()).orElseThrow();
        ZoneId tokyoZone = ZoneId.of("Asia/Tokyo");
        ZonedDateTime tokyoMidnight = LocalDate.now(tokyoZone).plusDays(1).atStartOfDay(tokyoZone);
        Instant afterMidnight = tokyoMidnight.toInstant().plusSeconds(30);

        leaseRecoveryService.settleMemberIfCrossedMidnight(member, afterMidnight);

        SessionLease settled = leaseRepository.findByLeaseToken(lease.leaseToken()).orElseThrow();
        assertNotEquals("ACTIVE", settled.getStatus().name(),
                "Lease should be settled after Tokyo local midnight");
        assertNotNull(settled.getEndedAt());

        MemberResponse nyAdult = memberService.createMember(new CreateMemberRequest(
                "NY Adult", "ADULT", "America/New_York", null, null, null));
        LeaseResponse nyLease = leaseService.acquireLease(new AcquireLeaseRequest(
                nyAdult.memberId(), "key-ny", 15, "device-1"));

        FamilyMember nyMember = memberRepository.findByMemberUuid(nyAdult.memberId()).orElseThrow();
        SessionLease stillActive = leaseRepository.findByLeaseToken(nyLease.leaseToken()).orElseThrow();
        assertEquals("ACTIVE", stillActive.getStatus().name(),
                "NY member lease should remain active when Tokyo has crossed midnight but NY has not");
    }

    @Test
    void outboxWebhook_realHttpDelivery_realPostgres() throws Exception {
        outboxService.setWebhookUrl("http://localhost:" + port + "/test-webhook");

        MemberResponse adult = createAdult("UTC");
        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        outboxService.publishPendingEvents();

        List<Map<String, Object>> events = webhookController.getReceivedEvents();
        assertFalse(events.isEmpty(), "Outbox webhook should have delivered at least one event via HTTP");

        boolean hasLeaseAcquired = events.stream()
                .anyMatch(e -> "LEASE_ACQUIRED".equals(e.get("event_type")));
        assertTrue(hasLeaseAcquired, "Should have received LEASE_ACQUIRED event via webhook, got: " + events);

        Integer sentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'SENT'", Integer.class);
        assertNotNull(sentCount);
        assertTrue(sentCount >= 1, "Events should be marked SENT in DB after delivery");
    }

    @Test
    void outboxWebhook_retryOnFailure_realPostgres() throws Exception {
        outboxService.setWebhookUrl("http://localhost:19876/no-such-server");

        MemberResponse adult = createAdult("UTC");
        leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "key-1", 15, "device-1"));

        outboxService.publishPendingEvents();

        Integer pendingAfterFirstTry = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING' AND retry_count = 1", Integer.class);
        assertNotNull(pendingAfterFirstTry);
        assertEquals(1, pendingAfterFirstTry.intValue(),
                "After first failed delivery, event should be PENDING with retry_count=1, got: " + pendingAfterFirstTry);

        outboxService.publishPendingEvents();

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING' AND retry_count >= 1", Integer.class);
        assertNotNull(pendingCount);
        assertTrue(pendingCount >= 1, "Failed events should remain PENDING for retry");

        outboxService.setWebhookUrl("http://localhost:" + port + "/test-webhook");
        outboxService.publishPendingEvents();

        List<Map<String, Object>> events = webhookController.getReceivedEvents();
        assertFalse(events.isEmpty(), "After fixing webhook URL, retried events should be delivered via HTTP");

        Integer sentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'SENT'", Integer.class);
        assertNotNull(sentCount);
        assertTrue(sentCount >= 1, "Events should be marked SENT after successful retry delivery");
    }

    @Test
    void multipleMembers_concurrentLeases_realPostgres() throws Exception {
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

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        for (Future<LeaseResponse> f : futures) {
            LeaseResponse lease = f.get(10, TimeUnit.SECONDS);
            assertEquals("ACTIVE", lease.status());
            assertEquals(15, lease.grantedMinutes().intValue());
        }
    }

    @Test
    void quotaDeduction_consistentAcrossRetries_realPostgres() {
        MemberResponse adult = createAdult("UTC");

        LeaseResponse lease = leaseService.acquireLease(new AcquireLeaseRequest(
                adult.memberId(), "idem-key", 15, "device-1"));

        assertThrows(com.family.scrollshield.exception.IdempotentDuplicateException.class, () ->
                leaseService.acquireLease(new AcquireLeaseRequest(
                        adult.memberId(), "idem-key", 15, "device-1")));

        FamilyMember member = memberRepository.findByMemberUuid(adult.memberId()).orElseThrow();
        var usage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), LocalDate.now(ZoneOffset.UTC)).orElseThrow();
        assertEquals(15, usage.getUsedMinutes().intValue(),
                "Idempotent retry must not double-deduct quota on real PostgreSQL");
        assertEquals(45, usage.getRemainingMinutes().intValue());
    }
}
