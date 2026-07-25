package com.family.scrollshield.repository;

import com.family.scrollshield.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);
}
