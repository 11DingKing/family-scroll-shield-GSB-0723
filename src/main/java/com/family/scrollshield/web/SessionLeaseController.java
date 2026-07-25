package com.family.scrollshield.web;

import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.HeartbeatRequest;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.dto.ReleaseRequest;
import com.family.scrollshield.service.LeaseService;
import com.family.scrollshield.service.ViewingPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leases")
public class SessionLeaseController {

    private final LeaseService leaseService;
    private final ViewingPlanService planService;

    public SessionLeaseController(LeaseService leaseService, ViewingPlanService planService) {
        this.leaseService = leaseService;
        this.planService = planService;
    }

    @PostMapping("/acquire")
    public ResponseEntity<LeaseResponse> acquire(@Valid @RequestBody LeaseRequest request) {
        SessionLease lease = leaseService.acquire(request.memberExternalId());
        int remaining = planRemaining(lease);
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaseResponse.of(lease, remaining));
    }

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        return leaseService.heartbeat(request.leaseToken(), request.clientNow());
    }

    @PostMapping("/release")
    public LeaseResponse release(@Valid @RequestBody ReleaseRequest request) {
        SessionLease lease = leaseService.release(request.leaseToken());
        return LeaseResponse.of(lease, planRemaining(lease));
    }

    private int planRemaining(SessionLease lease) {
        return planService.remainingByPlanId(lease.getPlanId());
    }
}
