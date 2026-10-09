package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxClaimService {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;

    @Value("${eventhub.outbox.processing-timeout:PT10M}")
    private Duration processingTimeout = Duration.ofMinutes(10);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPendingEvents() {

        if (processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalStateException("Outbox processing timeout must be positive");
        }

        int recovered = outboxEventRepository.recoverStaleProcessingEvents(
                LocalDateTime.now().minus(processingTimeout));
        if (recovered > 0) {
            log.warn("Recovered {} stale PROCESSING outbox events", recovered);
        }

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
