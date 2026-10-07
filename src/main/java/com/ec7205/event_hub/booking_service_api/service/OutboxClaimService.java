package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPendingEvents() {

        List<OutboxEvent> events =
                outboxEventRepository
                        .findPendingEventsForUpdate("PENDING")
                        .stream()
                        .limit(BATCH_SIZE)
                        .toList();

        LocalDateTime now = LocalDateTime.now();

        for (OutboxEvent event : events) {
            event.setStatus("PROCESSING");
            event.setProcessingStartedAt(now);
        }

        return events;
    }
}