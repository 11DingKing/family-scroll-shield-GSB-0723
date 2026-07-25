package com.family.scrollshield.service;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers and looks up family members. Member creation records a
 * {@code MEMBER_CREATED} event in the same transaction (audit + outbox).
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final QuotaPolicyService quotaPolicy;
    private final OutboxService outbox;

    public MemberService(MemberRepository memberRepository,
                         QuotaPolicyService quotaPolicy,
                         OutboxService outbox) {
        this.memberRepository = memberRepository;
        this.quotaPolicy = quotaPolicy;
        this.outbox = outbox;
    }

    /** Payload persisted to the outbox when a member is created. */
    public record MemberCreatedPayload(UUID memberId, String externalId, AgeGroup ageGroup,
                                       String timeZone, String bedtimeLocal) {
    }

    @Transactional
    public Member create(MemberRequest request) {
        // Validate the time zone eagerly so a bad zone never reaches persistence.
        quotaPolicy.zoneOf(request.timeZone());

        LocalTime bedtime = parseBedtime(request.bedtimeLocal());
        if (request.ageGroup() == AgeGroup.TEEN && bedtime == null) {
            throw new LeaseException(LeaseException.Code.INVALID_REQUEST,
                    "bedtimeLocal is required for TEEN members");
        }

        if (memberRepository.existsByExternalId(request.externalId())) {
            throw new LeaseException(LeaseException.Code.INVALID_REQUEST,
                    "member already exists: " + request.externalId());
        }

        Member member = Member.builder()
                .id(UUID.randomUUID())
                .externalId(request.externalId())
                .displayName(request.displayName())
                .ageGroup(request.ageGroup())
                .timeZone(request.timeZone())
                .bedtimeLocal(bedtime)
                .build();
        member = memberRepository.save(member);

        outbox.record(AggregateTypes.MEMBER, member.getId(), EventTypes.MEMBER_CREATED,
                new MemberCreatedPayload(member.getId(), member.getExternalId(),
                        member.getAgeGroup(), member.getTimeZone(),
                        bedtime == null ? null : bedtime.toString()));
        return member;
    }

    @Transactional(readOnly = true)
    public Member requireByExternalId(String externalId) {
        return memberRepository.findByExternalId(externalId)
                .orElseThrow(() -> new LeaseException(LeaseException.Code.MEMBER_NOT_FOUND,
                        "member not found: " + externalId));
    }

    @Transactional(readOnly = true)
    public Optional<Member> findByExternalId(String externalId) {
        return memberRepository.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Member requireById(UUID memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new LeaseException(LeaseException.Code.MEMBER_NOT_FOUND,
                        "member not found: " + memberId));
    }

    private LocalTime parseBedtime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new LeaseException(LeaseException.Code.INVALID_REQUEST,
                    "invalid bedtimeLocal (expected HH:mm): " + value);
        }
    }
}
