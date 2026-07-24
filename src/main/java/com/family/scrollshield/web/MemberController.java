package com.family.scrollshield.web;

import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.dto.MemberResponse;
import com.family.scrollshield.service.MemberService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse create(@Valid @RequestBody MemberRequest req) {
        return memberService.create(req);
    }

    @GetMapping("/{id}")
    public MemberResponse get(@PathVariable UUID id) {
        return memberService.get(id);
    }
}
