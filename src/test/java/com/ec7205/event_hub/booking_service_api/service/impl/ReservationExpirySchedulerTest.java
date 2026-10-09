package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.service.ReservationExpiryScheduler;
import com.ec7205.event_hub.booking_service_api.service.ReservationExpiryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class ReservationExpirySchedulerTest {
    @Test
    void oneFailedReleaseDoesNotPreventOtherBookingsOrLaterPolls() {
        BookingRepository bookings = mock(BookingRepository.class);
        ReservationExpiryService expiry = mock(ReservationExpiryService.class);
        when(bookings.findExpiredReservationIds(eq(BookingStatus.PENDING), any(), any()))
                .thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("Event Service unavailable"))
                .doNothing().when(expiry).expireReservation(eq(1L), any());
        ReservationExpiryScheduler scheduler = new ReservationExpiryScheduler(bookings, expiry);
        scheduler.expireReservations();
        scheduler.expireReservations();
        verify(expiry, times(2)).expireReservation(eq(1L), any());
        verify(expiry, times(2)).expireReservation(eq(2L), any());
    }
}
