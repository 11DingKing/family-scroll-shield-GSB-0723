package com.family.scrollshield.repository;

import com.family.scrollshield.domain.LeaseHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaseHeartbeatRepository extends JpaRepository<LeaseHeartbeat, Long> {

    List<LeaseHeartbeat> findByLeaseIdOrderByObservedAtAsc(UUID leaseId);
}
