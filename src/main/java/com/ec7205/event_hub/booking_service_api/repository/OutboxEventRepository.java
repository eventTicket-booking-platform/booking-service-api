package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OutboxEvent o
        set o.status = 'PENDING', o.processingStartedAt = null,
            o.version = o.version + 1
        where o.status = 'PROCESSING' and o.processingStartedAt < :cutoff
    """)
    int recoverStaleProcessingEvents(@Param("cutoff") LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o
        from OutboxEvent o
        where o.status = :status
        order by o.createdAt asc
    """)
    List<OutboxEvent> findPendingEventsForUpdate(
            @Param("status") String status
    );
}
