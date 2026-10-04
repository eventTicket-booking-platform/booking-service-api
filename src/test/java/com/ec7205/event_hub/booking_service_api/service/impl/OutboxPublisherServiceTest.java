package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.ec7205.event_hub.booking_service_api.service.OutboxPublisherService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


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
        private BookingNotificationEventPublisher publisher;

        @Mock
        private ObjectMapper objectMapper;

        @InjectMocks
        private OutboxPublisherService outboxPublisherService;

        @Test
        void shouldMarkEventSentWhenPublishSucceeds() throws Exception {

            OutboxEvent event = OutboxEvent.builder()
                    .id(1L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-1\"}")
                    .status("PENDING")
                    .retryCount(0)
                    .build();

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
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

            verify(publisher, times(1))
                    .publish(any(BookingNotificationEvent.class));
        }

        @Test
        void shouldIncrementRetryCountWhenPublishFails() throws Exception {

            OutboxEvent event = OutboxEvent.builder()
                    .id(1L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-1\"}")
                    .status("PENDING")
                    .retryCount(0)
                    .build();

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
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
        }

        @Test
        void shouldMarkEventFailedAfterMaximumRetries() throws Exception {

            OutboxEvent event = OutboxEvent.builder()
                    .id(1L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-1\"}")
                    .status("PENDING")
                    .retryCount(2)
                    .build();

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
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
        }

        @Test
        void shouldDoNothingWhenThereAreNoPendingEvents() {

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
                    .thenReturn(List.of());

            outboxPublisherService.publishPendingEvents();

            verifyNoInteractions(publisher);
            verifyNoInteractions(objectMapper);
        }

        @Test
        void shouldPublishCorrectEventTypeAndPayload() throws Exception {

            OutboxEvent event = OutboxEvent.builder()
                    .id(1L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-123\"}")
                    .status("PENDING")
                    .retryCount(0)
                    .build();

            Map<String, Object> payload =
                    Map.of(
                            "bookingId", "BK-123",
                            "email", "user@test.com"
                    );

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
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
        }

        @Test
        void shouldContinueProcessingOtherEventsWhenOneFails()
                throws Exception {

            OutboxEvent first = OutboxEvent.builder()
                    .id(1L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-1\"}")
                    .status("PENDING")
                    .retryCount(0)
                    .build();

            OutboxEvent second = OutboxEvent.builder()
                    .id(2L)
                    .eventType("BOOKING_CONFIRMED")
                    .payload("{\"bookingId\":\"BK-2\"}")
                    .status("PENDING")
                    .retryCount(0)
                    .build();

            when(outboxEventRepository
                    .findPendingEventsForUpdate("PENDING"))
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

            assertEquals("SENT", second.getStatus());
            assertNotNull(second.getPublishedAt());

            verify(publisher, times(2))
                    .publish(any(BookingNotificationEvent.class));
        }

    }

