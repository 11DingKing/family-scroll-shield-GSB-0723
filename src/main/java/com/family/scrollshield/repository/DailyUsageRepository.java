package com.family.scrollshield.repository;

import com.family.scrollshield.domain.DailyUsage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailyUsageRepository extends JpaRepository<DailyUsage, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DailyUsage d WHERE d.member.id = :memberId AND d.usageDate = :date")
    Optional<DailyUsage> findByMemberAndDateForUpdate(@Param("memberId") Long memberId, @Param("date") LocalDate date);

    Optional<DailyUsage> findByMemberIdAndUsageDate(Long memberId, LocalDate date);

    @Query("SELECT d FROM DailyUsage d WHERE d.member.id = :memberId AND d.usageDate = :date")
    Optional<DailyUsage> findByMemberAndDateNoLock(@Param("memberId") Long memberId, @Param("date") LocalDate date);
}
