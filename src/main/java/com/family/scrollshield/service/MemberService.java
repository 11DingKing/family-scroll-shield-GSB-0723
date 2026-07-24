package com.family.scrollshield.service;

import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.MemberRole;
import com.family.scrollshield.dto.request.CreateMemberRequest;
import com.family.scrollshield.dto.response.MemberResponse;
import com.family.scrollshield.exception.MemberNotFoundException;
import com.family.scrollshield.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final FamilyMemberRepository memberRepository;
    private final QuotaService quotaService;

    @Transactional
    public MemberResponse createMember(CreateMemberRequest request) {
        MemberRole role = MemberRole.valueOf(request.role().toUpperCase());

        FamilyMember member = FamilyMember.builder()
                .memberUuid(UUID.randomUUID())
                .name(request.name())
                .role(role)
                .timezone(request.timezone() != null ? request.timezone() : "UTC")
                .bedtimeLocal(request.bedtimeLocal())
                .dailyLimitMin(request.dailyLimitMin())
                .singleLimitMin(request.singleLimitMin())
                .build();

        if (request.dailyLimitMin() == null) {
            member.setDailyLimitMin(quotaService.getDailyLimit(member));
        }
        if (request.singleLimitMin() == null) {
            member.setSingleLimitMin(quotaService.getSingleSessionLimit(member));
        }

        member = memberRepository.save(member);
        return toResponse(member);
    }

    @Transactional(readOnly = true)
    public FamilyMember getEntityByUuid(UUID uuid) {
        return memberRepository.findByMemberUuid(uuid)
                .orElseThrow(() -> new MemberNotFoundException(uuid));
    }

    @Transactional(readOnly = true)
    public MemberResponse getMember(UUID uuid) {
        return toResponse(getEntityByUuid(uuid));
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers() {
        return memberRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    private MemberResponse toResponse(FamilyMember m) {
        return new MemberResponse(
                m.getMemberUuid(),
                m.getName(),
                m.getRole().name(),
                m.getTimezone(),
                m.getBedtimeLocal(),
                m.getDailyLimitMin(),
                m.getSingleLimitMin(),
                m.getCreatedAt()
        );
    }
}
