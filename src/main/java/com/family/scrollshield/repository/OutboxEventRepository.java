package com.family.scrollshield.repository;

import com.family.scrollshield.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            select * from outbox_events
            where published = false
            order by id asc
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("limit") int limit);

    @Modifying
    @Query("""
            update OutboxEvent o set o.published = true, o.publishedAt = :now
            where o.id in :ids
            """)
    int markPublished(@Param("ids") List<Long> ids, @Param("now") Instant now);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, UUID aggregateId);

    @Modifying
    @Query("""
            delete from OutboxEvent o
            where o.published = true and o.publishedAt < :threshold
            """)
    int deletePublishedBefore(@Param("threshold") Instant threshold);
}
