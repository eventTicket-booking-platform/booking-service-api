package com.ec7205.event_hub.booking_service_api.service.impl;
import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.ec7205.event_hub.booking_service_api.service.OutboxPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
@SpringBootTest
@ActiveProfiles("test")
class OutboxPublisherConcurrencyTest {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private BookingNotificationEventPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        outboxEventRepository.deleteAll();

        OutboxEvent event = OutboxEvent.builder()
                .eventType("BOOKING_CONFIRMED")
                .aggregateType("BOOKING")
                .aggregateId("BK-TEST-1")
                .correlationId( "test-correlation-id")
                .payload("""
                        {
                          "bookingId":"BK-TEST-1",
                          "email":"user@test.com"
                        }
                        """)
                .status("PENDING")
                .retryCount(0)
                .build();

        outboxEventRepository.saveAndFlush(event);
    }

    @Test
    void shouldPublishSameOutboxEventOnlyOnceWhenTwoWorkersRun()
            throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        Callable<Void> task = () -> {

            ready.countDown();
            start.await();

            outboxPublisherService.publishPendingEvents();

            return null;
        };

        Future<Void> first =
                executor.submit(task);

        Future<Void> second =
                executor.submit(task);

        ready.await();
        start.countDown();

        first.get();
        second.get();

        executor.shutdown();

        verify(publisher, times(1))
                .publish(any(BookingNotificationEvent.class));

        List<OutboxEvent> events =
                outboxEventRepository.findAll();

        assertEquals(1, events.size());

        OutboxEvent event =
                events.get(0);

        assertEquals(
                "SENT",
                event.getStatus()
        );

        assertNotNull(
                event.getPublishedAt()
        );
    }
}