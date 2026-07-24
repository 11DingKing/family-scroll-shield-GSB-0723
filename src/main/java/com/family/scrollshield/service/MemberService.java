package com.family.scrollshield.service;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.dto.MemberResponse;
import com.family.scrollshield.repository.MemberRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final QuotaPolicyService quotaPolicy;
    private final OutboxService outbox;

    @Transactional
    public MemberResponse create(MemberRequest req) {
        quotaPolicy.resolveZone(req.timeZone());
        if (req.ageGroup() == AgeGroup.TEEN && req.bedtimeLocal() == null) {
            throw new LeaseException("BEDTIME_REQUIRED", "青少年必须设置睡前时间");
        }
        Member m = Member.builder()
                .id(UUID.randomUUID())
                .externalId(req.externalId())
                .displayName(req.displayName())
                .ageGroup(req.ageGroup())
                .timeZone(req.timeZone())
                .bedtimeLocal(req.bedtimeLocal())
                .build();
        memberRepository.save(m);
        outbox.record(AggregateTypes.MEMBER, m.getId(), EventTypes.MEMBER_CREATED,
                new MemberCreatedPayload(m.getId(), m.getExternalId(), m.getAgeGroup().name(),
                        m.getTimeZone(), m.getBedtimeLocal(), Instant.now()));
        return toResponse(m);
    }

    @Transactional(readOnly = true)
    public MemberResponse get(UUID id) {
        return toResponse(load(id));
    }

    public Member load(UUID id) {
        return memberRepository.findById(id).orElseThrow(LeaseException::memberNotFound);
    }

    public MemberResponse toResponse(Member m) {
        return new MemberResponse(
                m.getId(),
                m.getExternalId(),
                m.getDisplayName(),
                m.getAgeGroup(),
                m.getTimeZone(),
                m.getBedtimeLocal(),
                m.getCreatedAt()
        );
    }

    public record MemberCreatedPayload(
            UUID memberId, String externalId, String ageGroup, String timeZone,
            java.time.LocalTime bedtimeLocal, Instant occurredAt
    ) {}
}
