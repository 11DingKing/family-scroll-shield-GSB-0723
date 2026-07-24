package com.family.scrollshield.controller;

import com.family.scrollshield.dto.request.AcquireLeaseRequest;
import com.family.scrollshield.dto.request.ReleaseLeaseRequest;
import com.family.scrollshield.dto.response.LeaseResponse;
import com.family.scrollshield.dto.response.ReleaseResponse;
import com.family.scrollshield.service.LeaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;

    @PostMapping("/acquire")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaseResponse acquireLease(@Valid @RequestBody AcquireLeaseRequest request) {
        return leaseService.acquireLease(request);
    }

    @PostMapping("/release")
    public ReleaseResponse releaseLease(@Valid @RequestBody ReleaseLeaseRequest request) {
        return leaseService.releaseLease(request.leaseToken(), request.idempotencyKey());
    }

    @GetMapping("/{leaseToken}")
    public LeaseResponse getLease(@PathVariable UUID leaseToken) {
        return leaseService.getLease(leaseToken);
    }
}
