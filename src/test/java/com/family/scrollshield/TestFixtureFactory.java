package com.family.scrollshield;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.repository.MemberRepository;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TestFixtureFactory {

    @Autowired
    private MemberRepository memberRepository;

    public Member createAdult(String tz) {
        return saveMember(AgeGroup.ADULT, tz, null);
    }

    public Member createTeen(String tz, LocalTime bedtime) {
        return saveMember(AgeGroup.TEEN, tz, bedtime);
    }

    public Member createChild(String tz) {
        return saveMember(AgeGroup.CHILD, tz, null);
    }

    private Member saveMember(AgeGroup age, String tz, LocalTime bedtime) {
        Member m = Member.builder()
                .id(UUID.randomUUID())
                .externalId("ext-" + UUID.randomUUID())
                .displayName(age.name() + "-" + UUID.randomUUID().toString().substring(0, 6))
                .ageGroup(age)
                .timeZone(tz)
                .bedtimeLocal(bedtime)
                .build();
        return memberRepository.save(m);
    }
}
