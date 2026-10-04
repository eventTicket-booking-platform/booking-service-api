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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final BookingNotificationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRIES = 3;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxEventRepository
                        .findPendingEventsForUpdate("PENDING")
                        .stream()
                        .limit(50)
                        .toList();

        for (OutboxEvent event : events) {

            try {

                Map<String, Object> payload =
                        objectMapper.readValue(
                                event.getPayload(),
                                new TypeReference<>() {}
                        );

                publisher.publish(
                        BookingNotificationEvent.builder()
                                .type(event.getEventType())
                                .payload(payload)
                                .build()
                );

                event.setStatus("SENT");
                event.setPublishedAt(LocalDateTime.now());

            } catch (Exception ex) {

                int newRetryCount =
                        event.getRetryCount() + 1;

                event.setRetryCount(newRetryCount);

                if (newRetryCount >= MAX_RETRIES) {
                    event.setStatus("FAILED");
                }

                log.warn(
                        "Failed to publish outbox event {} attempt {}/{}: {}",
                        event.getId(),
                        newRetryCount,
                        MAX_RETRIES,
                        ex.getMessage()
                );
            }
        }
    }
}