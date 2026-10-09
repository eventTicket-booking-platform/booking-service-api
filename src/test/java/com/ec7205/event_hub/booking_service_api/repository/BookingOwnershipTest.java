package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.client.*;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.service.IdempotencyService;
import com.ec7205.event_hub.booking_service_api.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({BookingServiceImpl.class, AdminBookingServiceImpl.class, BookingMapper.class})
class BookingOwnershipTest {
    @Autowired BookingRepository bookings;
    @Autowired BookingServiceImpl service;
    @Autowired AdminBookingServiceImpl admin;
    @MockBean EventServiceClient events;
    @MockBean AuthServiceClient users;
    @MockBean IdempotencyService idempotency;
    @MockBean BookingNotificationEventPublisher publisher;
    @MockBean ObjectMapper objectMapper;
    private Booking ownEventBooking;
    private Booking otherEventBooking;

    @BeforeEach void setup() {
        ownEventBooking = booking(100L, "customer-1", BookingStatus.CONFIRMED, 10);
        booking(100L, "customer-2", BookingStatus.PENDING, 20);
        otherEventBooking = booking(200L, "customer-2", BookingStatus.CONFIRMED, 90);
        when(events.getOwnedEventIds("Bearer host-1")).thenReturn(List.of(100L));
        when(events.getEventBookingInfo(100L)).thenReturn(EventBookingInfoResponse.builder().createdBy("host-1").build());
        when(events.getEventBookingInfo(200L)).thenReturn(EventBookingInfoResponse.builder().createdBy("host-2").build());
    }

    @Test void hostCanViewOwnedEventBookingDetailsListsAndStatistics() {
        assertEquals(ownEventBooking.getId(), service.getBookingDetails(ownEventBooking.getId(), "host-1", "HOST").getBookingId());
        var page = admin.getAllBookings("HOST", "Bearer host-1", null, null, null, null, PageRequest.of(0, 1));
        assertEquals(2, page.getDataCount());
        assertEquals(100L, page.getDataList().get(0).getEventId());
        var stats = admin.getBookingStats("HOST", "Bearer host-1", null);
        assertEquals(2, stats.getTotalBookings());
        assertEquals(1, stats.getConfirmedBookings());
        assertEquals(1, stats.getPendingBookings());
        assertEquals(0, BigDecimal.TEN.compareTo(stats.getTotalRevenue()));
        assertEquals(2, admin.getBookingStats("HOST", "Bearer host-1", 100L).getTotalBookings());
    }

    @Test void hostCannotViewAnotherHostsBookingOrRequestItsListOrStatistics() {
        assertThrows(UnauthorizedActionException.class,
                () -> service.getBookingDetails(otherEventBooking.getId(), "host-1", "HOST"));
        assertThrows(UnauthorizedActionException.class,
                () -> admin.getAllBookings("HOST", "Bearer host-1", null, 200L, null, null, PageRequest.of(0, 20)));
        assertThrows(UnauthorizedActionException.class, () -> admin.getBookingStats("HOST", "Bearer host-1", 200L));
        assertThrows(UnauthorizedActionException.class,
                () -> admin.getAllBookings("HOST", "Bearer host-1", null, null, null, "victim@test.com", PageRequest.of(0, 20)));
        verifyNoInteractions(users);
    }

    @Test void adminCanViewAllBookingsAndPlatformStatistics() {
        assertEquals(otherEventBooking.getId(), service.getBookingDetails(otherEventBooking.getId(), "admin", "ADMIN").getBookingId());
        assertEquals(3, admin.getAllBookings("ADMIN", "Bearer admin", null, null, null, null, PageRequest.of(0, 20)).getDataCount());
        var stats = admin.getBookingStats("ADMIN", "Bearer admin", null);
        assertEquals(3, stats.getTotalBookings());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(stats.getTotalRevenue()));
        verify(events, never()).getOwnedEventIds(anyString());
    }

    @Test void customerCanStillViewOnlyOwnBookingDetailsAndHistory() {
        assertEquals(ownEventBooking.getId(), service.getBookingDetails(ownEventBooking.getId(), "customer-1", "CUSTOMER").getBookingId());
        assertThrows(UnauthorizedActionException.class,
                () -> service.getBookingDetails(otherEventBooking.getId(), "customer-1", "CUSTOMER"));
        var mine = service.getMyBookings("customer-1", PageRequest.of(0, 20));
        assertEquals(1, mine.getDataCount());
        assertEquals(ownEventBooking.getId(), mine.getDataList().get(0).getBookingId());
        assertThrows(UnauthorizedActionException.class, () -> admin.getBookingStats("CUSTOMER", "Bearer customer", null));
        assertThrows(UnauthorizedActionException.class,
                () -> admin.getAllBookings("CUSTOMER", "Bearer customer", null, null, null, null, PageRequest.of(0, 20)));
    }

    @Test void hostWithNoEventsGetsEmptyListAndZeroStats() {
        when(events.getOwnedEventIds("Bearer empty-host")).thenReturn(List.of());
        assertEquals(0, admin.getAllBookings("HOST", "Bearer empty-host", null, null, null, null, PageRequest.of(0, 20)).getDataCount());
        assertEquals(0, admin.getBookingStats("HOST", "Bearer empty-host", null).getTotalBookings());
        assertEquals(BigDecimal.ZERO, admin.getBookingStats("HOST", "Bearer empty-host", null).getTotalRevenue());
    }

    @Test void failedOwnershipLookupNeverFallsBackToPlatformData() {
        when(events.getOwnedEventIds("Bearer host-1")).thenThrow(new IllegalStateException("Event Service unavailable"));
        assertThrows(IllegalStateException.class, () -> admin.getBookingStats("HOST", "Bearer host-1", null));
    }

    private Booking booking(Long eventId, String customer, BookingStatus status, int amount) {
        return bookings.saveAndFlush(Booking.builder().bookingReference(UUID.randomUUID().toString().substring(0, 32))
                .userId(customer).eventId(eventId).eventTitleSnapshot("Test").eventBannerResourceUrlSnapshot("banner")
                .eventStartDateTimeSnapshot(LocalDateTime.now().plusDays(1)).totalAmount(BigDecimal.valueOf(amount))
                .status(status).idempotencyKey(UUID.randomUUID().toString()).requestFingerprint("fingerprint").build());
    }
}
