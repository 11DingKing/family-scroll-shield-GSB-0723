package com.family.scrollshield.repository;

import com.family.scrollshield.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select e from OutboxEvent e where e.published = false order by e.id asc")
    List<OutboxEvent> findUnpublished(Pageable pageable);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, UUID aggregateId);

    @Query("select e from OutboxEvent e where e.aggregateId = :aggregateId order by e.id asc")
    List<OutboxEvent> findByAggregateId(@Param("aggregateId") UUID aggregateId);
}
