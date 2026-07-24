package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.ExtensionRequest;
import com.family.scrollshield.dto.ExtensionResponse;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.repository.ExtensionApprovalRepository;
import com.family.scrollshield.service.ExtensionApprovalService;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.LeaseService;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExtensionApprovalTest extends AbstractIntegrationTest {

    @Autowired
    private ExtensionApprovalService extensionService;

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private ExtensionApprovalRepository approvalRepository;

    @Autowired
    private TestFixtureFactory fixtures;

    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setSystem();
    }

    @Test
    void grantsFiveMinutesOncePerDay() {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse lease = leaseService.acquire(new LeaseRequest(adult.getId(), "c1", null));

        ExtensionResponse first = extensionService.grant(
                new ExtensionRequest(adult.getId(), lease.leaseId(), "parent", "please"));
        assertThat(first.grantedSeconds()).isEqualTo(300);
        assertThat(first.applied()).isTrue();

        ViewingPlan plan = planRepository.findById(lease.planId()).orElseThrow();
        assertThat(plan.isExtensionUsed()).isTrue();
        assertThat(plan.getExtensionSeconds()).isEqualTo(300);
        assertThat(approvalRepository.countByPlan(plan.getId())).isEqualTo(1);

        assertThatThrownBy(() -> extensionService.grant(
                new ExtensionRequest(adult.getId(), lease.leaseId(), "parent", "again")))
                .isInstanceOf(LeaseException.class)
                .matches(e -> ((LeaseException) e).getCode().equals("EXTENSION_ALREADY_USED"));
    }

    @Test
    void extensionDoesNotBreakBedtimeWindow() {
        Member teen = fixtures.createTeen("Asia/Shanghai", LocalTime.of(22, 0));
        LeaseResponse lease = leaseService.acquire(new LeaseRequest(teen.getId(), "c1", null));

        ZonedDateTime nearBlackout = ZonedDateTime.of(2026, 7, 24, 20, 58, 0, 0, ZoneId.of("Asia/Shanghai"));
        clock.setFixed(nearBlackout.toInstant(), ZoneId.of("Asia/Shanghai"));

        assertThatThrownBy(() -> extensionService.grant(
                new ExtensionRequest(teen.getId(), lease.leaseId(), "parent", null)))
                .isInstanceOf(LeaseException.class)
                .matches(e -> ((LeaseException) e).getCode().equals("EXTENSION_BLOCKED_BY_BEDTIME"));
    }

    @Test
    void extensionGrantsWhenBeforeBedtimeWindow() {
        Member teen = fixtures.createTeen("Asia/Shanghai", LocalTime.of(22, 0));
        LeaseResponse lease = leaseService.acquire(new LeaseRequest(teen.getId(), "c1", null));

        ZonedDateTime early = ZonedDateTime.of(2026, 7, 24, 18, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        clock.setFixed(early.toInstant(), ZoneId.of("Asia/Shanghai"));

        ExtensionResponse r = extensionService.grant(
                new ExtensionRequest(teen.getId(), lease.leaseId(), "parent", "ok"));
        assertThat(r.grantedSeconds()).isEqualTo(300);
        assertThat(r.applied()).isTrue();
    }

    @Test
    void extensionWithoutActiveLeaseStillIncreasesBudget() {
        Member fresh = fixtures.createAdult("UTC");
        LeaseResponse freshLease = leaseService.acquire(new LeaseRequest(fresh.getId(), "c1", null));
        leaseService.release(freshLease.leaseToken(), "released");

        clock.setSystem();
        ExtensionResponse r = extensionService.grant(
                new ExtensionRequest(fresh.getId(), null, "parent", "no active lease"));
        assertThat(r.grantedSeconds()).isEqualTo(300);
        assertThat(r.applied()).isFalse();

        ViewingPlan plan = planRepository.findById(freshLease.planId()).orElseThrow();
        assertThat(plan.getExtensionSeconds()).isEqualTo(300);
        assertThat(plan.isExtensionUsed()).isTrue();
    }
}
