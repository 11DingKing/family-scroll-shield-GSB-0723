package com.family.scrollshield.repository;

import com.family.scrollshield.domain.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, UUID> {
    Optional<Member> findByExternalId(String externalId);
}
