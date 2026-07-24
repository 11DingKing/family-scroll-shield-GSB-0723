package com.family.scrollshield.controller;

import com.family.scrollshield.dto.request.CreateMemberRequest;
import com.family.scrollshield.dto.response.MemberResponse;
import com.family.scrollshield.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse createMember(@Valid @RequestBody CreateMemberRequest request) {
        return memberService.createMember(request);
    }

    @GetMapping("/{memberId}")
    public MemberResponse getMember(@PathVariable UUID memberId) {
        return memberService.getMember(memberId);
    }

    @GetMapping
    public List<MemberResponse> listMembers() {
        return memberService.listMembers();
    }
}
