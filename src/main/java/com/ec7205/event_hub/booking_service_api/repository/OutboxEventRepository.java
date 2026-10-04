package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

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