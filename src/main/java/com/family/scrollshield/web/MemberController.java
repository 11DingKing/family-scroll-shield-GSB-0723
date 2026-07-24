package com.family.scrollshield.web;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.dto.MemberResponse;
import com.family.scrollshield.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping
    public ResponseEntity<MemberResponse> create(@Valid @RequestBody MemberRequest request) {
        Member member = memberService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member));
    }

    @GetMapping("/{externalId}")
    public MemberResponse get(@PathVariable String externalId) {
        return MemberResponse.from(memberService.requireByExternalId(externalId));
    }
}
