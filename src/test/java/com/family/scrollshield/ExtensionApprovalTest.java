package com.family.scrollshield;

import com.family.scrollshield.domain.ExtensionApproval;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.ViewingPlanRepository;
import com.family.scrollshield.service.ExtensionApprovalService;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.QuotaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The daily extension is a bedtime-safe, once-per-day, 5-minute grant that raises the
 * authoritative budget even without an active lease and is refused inside the bedtime
 * blackout window.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class ExtensionApprovalTest extends AbstractIntegrationTest {

    @Autowired
    ExtensionApprovalService extensionService;
    @Autowired
    ViewingPlanRepository planRepository;
    @Autowired
    TestFixtureFactory fixtures;
    @Autowired
    LeaseTestSupport support;

    @BeforeEach
    void reset() {
        support.flushRedis();
    }

    private void setLocalTime(ZoneId zone, int year, int month, int day, int hour, int minute) {
        CLOCK.setInstant(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant());
    }

    @Test
    void grantsFiveMinutesOncePerDay() {
        ZoneId zone = ZoneId.of("America/New_York");
        setLocalTime(zone, 2026, 1, 15, 15, 0); // mid-afternoon, far from bedtime
        Member teen = fixtures.teen("America/New_York", "21:00");

        ExtensionApproval first = extensionService.approve(teen.getExternalId(), "mom", "homework break");
        assertThat(first.getGrantedSeconds()).isEqualTo(QuotaPolicyService.MAX_EXTENSION_SECONDS);

        // A second same-day approval must be rejected.
        LeaseException ex = catchThrowableOfType(
                () -> extensionService.approve(teen.getExternalId(), "dad", "again"),
                LeaseException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(LeaseException.Code.EXTENSION_ALREADY_USED);
    }

    @Test
    void extensionGrantsWhenBeforeBedtimeWindow() {
        ZoneId zone = ZoneId.of("America/New_York");
        // 19:50 local, blackout starts 20:00 -> 10 min of headroom, so the full 5 min fits.
        setLocalTime(zone, 2026, 1, 15, 19, 50);
        Member teen = fixtures.teen("America/New_York", "21:00");

        ExtensionApproval approval = extensionService.approve(teen.getExternalId(), "mom", null);
        assertThat(approval.getGrantedSeconds()).isEqualTo(QuotaPolicyService.MAX_EXTENSION_SECONDS);
    }

    @Test
    void extensionDoesNotBreakBedtimeWindow() {
        ZoneId zone = ZoneId.of("America/New_York");
        // 20:30 local: inside the 20:00-21:00 blackout -> extension must be refused.
        setLocalTime(zone, 2026, 1, 15, 20, 30);
        Member teen = fixtures.teen("America/New_York", "21:00");

        LeaseException ex = catchThrowableOfType(
                () -> extensionService.approve(teen.getExternalId(), "mom", null),
                LeaseException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(LeaseException.Code.BEDTIME_BLACKOUT);
    }

    @Test
    void extensionWithoutActiveLeaseStillIncreasesBudget() {
        ZoneId zone = ZoneId.of("America/New_York");
        setLocalTime(zone, 2026, 1, 15, 14, 0);
        Member adult = fixtures.adult("America/New_York");

        ExtensionApproval approval = extensionService.approve(adult.getExternalId(), "self", null);
        LocalDate planDate = LocalDate.of(2026, 1, 15);
        ViewingPlan plan = planRepository.findByMemberIdAndPlanDate(adult.getId(), planDate).orElseThrow();

        assertThat(plan.isExtensionUsed()).isTrue();
        assertThat(plan.getExtensionSeconds()).isEqualTo(QuotaPolicyService.MAX_EXTENSION_SECONDS);
        assertThat(plan.totalBudgetSeconds())
                .isEqualTo(QuotaPolicyService.ADULT_DAILY_SECONDS + QuotaPolicyService.MAX_EXTENSION_SECONDS);
        assertThat(approval.getGrantedSeconds()).isEqualTo(QuotaPolicyService.MAX_EXTENSION_SECONDS);
    }
}
