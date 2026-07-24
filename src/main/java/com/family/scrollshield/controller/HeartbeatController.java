package com.family.scrollshield.controller;

import com.family.scrollshield.dto.request.HeartbeatRequest;
import com.family.scrollshield.dto.response.HeartbeatResponse;
import com.family.scrollshield.service.HeartbeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class HeartbeatController {

    private final HeartbeatService heartbeatService;

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        return heartbeatService.heartbeat(request);
    }
}
