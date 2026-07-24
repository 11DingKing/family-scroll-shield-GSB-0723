package com.family.scrollshield.web;

import com.family.scrollshield.dto.HeartbeatRequest;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.dto.ReleaseRequest;
import com.family.scrollshield.service.LeaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leases")
@RequiredArgsConstructor
public class SessionLeaseController {

    private final LeaseService leaseService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaseResponse acquire(@Valid @RequestBody LeaseRequest req) {
        return leaseService.acquire(req);
    }

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@Valid @RequestBody HeartbeatRequest req) {
        return leaseService.heartbeat(req);
    }

    @PostMapping("/{token}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String token, @RequestBody(required = false) ReleaseRequest req) {
        leaseService.release(token, req == null ? null : req.reason());
    }
}
