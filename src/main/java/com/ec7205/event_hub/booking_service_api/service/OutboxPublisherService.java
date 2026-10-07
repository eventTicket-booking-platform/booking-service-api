package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxClaimService outboxClaimService;
    private final BookingNotificationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRIES = 3;

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxClaimService.claimPendingEvents();

        for (OutboxEvent event : events) {

            try {

                Map<String, Object> payload =
                        objectMapper.readValue(
                                event.getPayload(),
                                new TypeReference<>() {}
                        );

                publisher.publish(
                        BookingNotificationEvent.builder()
                                .eventId(event.getId().toString())
                                .type(event.getEventType())
                                .correlationId(event.getCorrelationId())
                                .payload(payload)
                                .build()
                );

                event.setStatus("SENT");
                event.setPublishedAt(LocalDateTime.now());
                event.setProcessingStartedAt(null);

            } catch (Exception ex) {

                int newRetryCount =
                        event.getRetryCount() + 1;

                event.setRetryCount(newRetryCount);

                if (newRetryCount >= MAX_RETRIES) {
                    event.setStatus("FAILED");
                } else {
                    event.setStatus("PENDING");
                }
                event.setProcessingStartedAt(null);

                log.warn(
                        "Failed to publish outbox event {} attempt {}/{}: {}",
                        event.getId(),
                        newRetryCount,
                        MAX_RETRIES,
                        ex.getMessage()
                );
            }

            outboxEventRepository.saveAndFlush(event);
        }
    }
}
