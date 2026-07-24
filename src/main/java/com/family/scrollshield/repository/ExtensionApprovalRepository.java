package com.family.scrollshield.repository;

import com.family.scrollshield.domain.ExtensionApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtensionApprovalRepository extends JpaRepository<ExtensionApproval, UUID> {

    @Query("""
            select count(e) from ExtensionApproval e
            where e.plan.id = :planId
            """)
    long countByPlan(@Param("planId") UUID planId);

    @Query("""
            select e from ExtensionApproval e
            where e.plan.id = :planId
            order by e.grantedAt desc
            """)
    List<ExtensionApproval> findByPlan(@Param("planId") UUID planId);
}
