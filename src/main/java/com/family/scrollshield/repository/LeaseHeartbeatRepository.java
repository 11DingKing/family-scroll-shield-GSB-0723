package com.family.scrollshield.repository;

import com.family.scrollshield.domain.LeaseHeartbeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaseHeartbeatRepository extends JpaRepository<LeaseHeartbeat, Long> {
    List<LeaseHeartbeat> findByLeaseIdOrderByObservedAtAsc(UUID leaseId);
}
