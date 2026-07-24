package com.family.scrollshield.web;

import com.family.scrollshield.dto.OutboxEventResponse;
import com.family.scrollshield.service.AggregateTypes;
import com.family.scrollshield.service.OutboxService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final OutboxService outboxService;

    @GetMapping("/{aggregateType}/{aggregateId}")
    public List<OutboxEventResponse> replay(
            @PathVariable String aggregateType,
            @PathVariable UUID aggregateId) {
        return outboxService.replay(aggregateType, aggregateId);
    }

    @GetMapping("/lease/{leaseId}")
    public List<OutboxEventResponse> replayLease(@PathVariable UUID leaseId) {
        return outboxService.replay(AggregateTypes.LEASE, leaseId);
    }
}
