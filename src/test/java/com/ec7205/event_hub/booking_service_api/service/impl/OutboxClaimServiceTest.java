package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.ec7205.event_hub.booking_service_api.service.OutboxClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxClaimServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private OutboxClaimService outboxClaimService;

    @Test
    void shouldMarkPendingEventsAsProcessingAndReturnThem() {

        OutboxEvent event = OutboxEvent.builder()
                .id(1L)
                .eventType("BOOKING_CONFIRMED")
                .payload("{\"bookingId\":\"BK-1\"}")
                .status("PENDING")
                .retryCount(0)
                .build();

        when(outboxEventRepository.findPendingEventsForUpdate("PENDING"))
                .thenReturn(List.of(event));

        List<OutboxEvent> claimedEvents =
                outboxClaimService.claimPendingEvents();

        assertEquals(1, claimedEvents.size());
        assertSame(event, claimedEvents.get(0));
        assertEquals("PROCESSING", event.getStatus());
        assertNotNull(event.getProcessingStartedAt());
    }

    @Test
    void shouldReturnEmptyResultWhenNoPendingEventsExist() {

        when(outboxEventRepository.findPendingEventsForUpdate("PENDING"))
                .thenReturn(List.of());

        List<OutboxEvent> claimedEvents =
                outboxClaimService.claimPendingEvents();

        assertTrue(claimedEvents.isEmpty());
    }
}
