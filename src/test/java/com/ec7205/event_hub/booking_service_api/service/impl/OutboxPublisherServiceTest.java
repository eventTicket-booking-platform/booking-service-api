package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.ec7205.event_hub.booking_service_api.service.OutboxClaimService;
import com.ec7205.event_hub.booking_service_api.service.OutboxPublisherService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxClaimService outboxClaimService;

    @Mock
    private BookingNotificationEventPublisher publisher;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxPublisherService outboxPublisherService;

    @Test
    void shouldMarkEventSentWhenPublishSucceeds() throws Exception {

        OutboxEvent event =
                claimedEvent(1L, "{\"bookingId\":\"BK-1\"}", 0);

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of(event));

        when(objectMapper.readValue(
                eq(event.getPayload()),
                any(TypeReference.class)
        )).thenReturn(
                Map.of("bookingId", "BK-1")
        );

        outboxPublisherService.publishPendingEvents();

        assertEquals("SENT", event.getStatus());
        assertEquals(0, event.getRetryCount());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getProcessingStartedAt());

        verify(publisher, times(1))
                .publish(any(BookingNotificationEvent.class));
        verify(outboxEventRepository)
                .saveAndFlush(event);
    }

    @Test
    void shouldIncrementRetryCountWhenPublishFails() throws Exception {

        OutboxEvent event =
                claimedEvent(1L, "{\"bookingId\":\"BK-1\"}", 0);

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of(event));

        when(objectMapper.readValue(
                eq(event.getPayload()),
                any(TypeReference.class)
        )).thenReturn(
                Map.of("bookingId", "BK-1")
        );

        doThrow(new RuntimeException("RabbitMQ unavailable"))
                .when(publisher)
                .publish(any(BookingNotificationEvent.class));

        outboxPublisherService.publishPendingEvents();

        assertEquals(1, event.getRetryCount());
        assertEquals("PENDING", event.getStatus());
        assertNull(event.getPublishedAt());
        assertNull(event.getProcessingStartedAt());

        verify(outboxEventRepository)
                .saveAndFlush(event);
    }

    @Test
    void shouldMarkEventFailedAfterMaximumRetries() throws Exception {

        OutboxEvent event =
                claimedEvent(1L, "{\"bookingId\":\"BK-1\"}", 2);

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of(event));

        when(objectMapper.readValue(
                eq(event.getPayload()),
                any(TypeReference.class)
        )).thenReturn(
                Map.of("bookingId", "BK-1")
        );

        doThrow(new RuntimeException("RabbitMQ unavailable"))
                .when(publisher)
                .publish(any(BookingNotificationEvent.class));

        outboxPublisherService.publishPendingEvents();

        assertEquals(3, event.getRetryCount());
        assertEquals("FAILED", event.getStatus());
        assertNull(event.getPublishedAt());
        assertNull(event.getProcessingStartedAt());

        verify(outboxEventRepository)
                .saveAndFlush(event);
    }

    @Test
    void shouldDoNothingWhenThereAreNoPendingEvents() {

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of());

        outboxPublisherService.publishPendingEvents();

        verifyNoInteractions(publisher);
        verifyNoInteractions(objectMapper);
        verify(outboxEventRepository, never())
                .saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void shouldPublishCorrectEventTypeAndPayload() throws Exception {

        OutboxEvent event =
                claimedEvent(1L, "{\"bookingId\":\"BK-123\"}", 0);

        Map<String, Object> payload =
                Map.of(
                        "bookingId", "BK-123",
                        "email", "user@test.com"
                );

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of(event));

        when(objectMapper.readValue(
                eq(event.getPayload()),
                any(TypeReference.class)
        )).thenReturn(payload);

        ArgumentCaptor<BookingNotificationEvent> captor =
                ArgumentCaptor.forClass(
                        BookingNotificationEvent.class
                );

        outboxPublisherService.publishPendingEvents();

        verify(publisher)
                .publish(captor.capture());

        BookingNotificationEvent published =
                captor.getValue();

        assertEquals(
                "BOOKING_CONFIRMED",
                published.getType()
        );

        assertEquals(
                "BK-123",
                published.getPayload().get("bookingId")
        );

        assertEquals(
                "user@test.com",
                published.getPayload().get("email")
        );

        verify(outboxEventRepository)
                .saveAndFlush(event);
    }

    @Test
    void shouldContinueProcessingOtherEventsWhenOneFails()
            throws Exception {

        OutboxEvent first =
                claimedEvent(1L, "{\"bookingId\":\"BK-1\"}", 0);

        OutboxEvent second =
                claimedEvent(2L, "{\"bookingId\":\"BK-2\"}", 0);

        when(outboxClaimService.claimPendingEvents())
                .thenReturn(List.of(first, second));

        when(objectMapper.readValue(
                anyString(),
                any(TypeReference.class)
        )).thenAnswer(invocation -> {

            String json = invocation.getArgument(0);

            if (json.contains("BK-1")) {
                return Map.of("bookingId", "BK-1");
            }

            return Map.of("bookingId", "BK-2");
        });

        doThrow(new RuntimeException("first publish failed"))
                .doNothing()
                .when(publisher)
                .publish(any(BookingNotificationEvent.class));

        outboxPublisherService.publishPendingEvents();

        assertEquals(1, first.getRetryCount());
        assertEquals("PENDING", first.getStatus());
        assertNull(first.getPublishedAt());
        assertNull(first.getProcessingStartedAt());

        assertEquals("SENT", second.getStatus());
        assertNotNull(second.getPublishedAt());
        assertNull(second.getProcessingStartedAt());

        verify(publisher, times(2))
                .publish(any(BookingNotificationEvent.class));
        verify(outboxEventRepository)
                .saveAndFlush(first);
        verify(outboxEventRepository)
                .saveAndFlush(second);
    }

    @Test
    void shouldContinueWhenRecoveredClaimRejectsLateWorkerSave() throws Exception {
        OutboxEvent first = claimedEvent(1L, "{}", 0);
        OutboxEvent second = claimedEvent(2L, "{}", 0);
        when(outboxClaimService.claimPendingEvents()).thenReturn(List.of(first, second));
        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenReturn(Map.of());
        when(outboxEventRepository.saveAndFlush(first))
                .thenThrow(new OptimisticLockingFailureException("Claim recovered"));

        assertDoesNotThrow(() -> outboxPublisherService.publishPendingEvents());

        verify(outboxEventRepository).saveAndFlush(second);
        verify(publisher, times(2)).publish(any(BookingNotificationEvent.class));
    }

    private OutboxEvent claimedEvent(
            Long id,
            String payload,
            int retryCount
    ) {

        return OutboxEvent.builder()
                .id(id)
                .eventType("BOOKING_CONFIRMED")
                .payload(payload)
                .status("PROCESSING")
                .retryCount(retryCount)
                .processingStartedAt(LocalDateTime.now())
                .build();
    }
}
