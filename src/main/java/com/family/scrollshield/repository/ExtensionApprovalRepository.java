package com.family.scrollshield.repository;

import com.family.scrollshield.domain.ExtensionApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExtensionApprovalRepository extends JpaRepository<ExtensionApproval, UUID> {

    List<ExtensionApproval> findByMemberIdAndPlanId(UUID memberId, UUID planId);

    long countByPlanId(UUID planId);
}
