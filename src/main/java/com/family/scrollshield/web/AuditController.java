package com.family.scrollshield.web;

import com.family.scrollshield.dto.OutboxEventResponse;
import com.family.scrollshield.repository.OutboxEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only audit endpoint exposing the outbox event log for a given aggregate, enabling
 * deterministic replay of every state change (member creation, plan open/close, lease
 * grant/heartbeat/terminate/extend).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final OutboxEventRepository outboxRepository;

    public AuditController(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @GetMapping("/aggregate/{aggregateId}")
    public List<OutboxEventResponse> byAggregate(@PathVariable UUID aggregateId) {
        return outboxRepository.findByAggregateId(aggregateId).stream()
                .map(OutboxEventResponse::from)
                .toList();
    }
}
