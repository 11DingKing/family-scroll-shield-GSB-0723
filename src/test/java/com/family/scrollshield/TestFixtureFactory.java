package com.family.scrollshield;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.service.MemberService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Convenience factory for seeding members in integration tests.
 */
@Component
public class TestFixtureFactory {

    private final MemberService memberService;

    public TestFixtureFactory(MemberService memberService) {
        this.memberService = memberService;
    }

    public Member adult(String timeZone) {
        return memberService.create(new MemberRequest(
                "adult-" + UUID.randomUUID(), "Adult", AgeGroup.ADULT, timeZone, null));
    }

    public Member teen(String timeZone, String bedtime) {
        return memberService.create(new MemberRequest(
                "teen-" + UUID.randomUUID(), "Teen", AgeGroup.TEEN, timeZone, bedtime));
    }

    public Member child(String timeZone) {
        return memberService.create(new MemberRequest(
                "child-" + UUID.randomUUID(), "Child", AgeGroup.CHILD, timeZone, null));
    }
}
