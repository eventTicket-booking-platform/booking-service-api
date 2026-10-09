package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.request.*;
import com.ec7205.event_hub.booking_service_api.dto.response.*;
import com.ec7205.event_hub.booking_service_api.entity.*;
import com.ec7205.event_hub.booking_service_api.enums.*;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.service.*;
import com.ec7205.event_hub.booking_service_api.service.impl.BookingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "eventhub.booking.reservation-timeout=PT2M")
@ActiveProfiles("test")
@Import({ReservationExpiryService.class, BookingServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationExpiryTest {
    @Autowired BookingRepository bookings;
    @Autowired ReservationExpiryService expiry;
    @Autowired BookingServiceImpl bookingService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean EventServiceClient events;
    @MockBean IdempotencyService idempotency;
    @MockBean BookingMapper mapper;
    @MockBean BookingNotificationEventPublisher publisher;
    @MockBean ObjectMapper objectMapper;

    @Test
    void expiredPendingReservationReleasesExactQuantitiesThenExpires() {
        Booking booking = pending(LocalDateTime.now().minusMinutes(1));
        doAnswer(call -> {
            assertEquals(BookingStatus.PENDING, bookings.findById(booking.getId()).orElseThrow().getStatus());
            return null;
        }).when(events).releaseTickets(eq(100L), any());

        expiry.expireReservation(booking.getId(), LocalDateTime.now());

        ArgumentCaptor<ReserveTicketsRequest> request = ArgumentCaptor.forClass(ReserveTicketsRequest.class);
        verify(events).releaseTickets(eq(100L), request.capture());
        assertEquals(booking.getBookingReference(), request.getValue().getReleaseId());
        assertEquals(List.of(1L, 2L), request.getValue().getTickets().stream()
                .map(TicketReservationRequest::getTicketTypeId).sorted().toList());
        assertEquals(2, request.getValue().getTickets().stream()
                .filter(t -> t.getTicketTypeId().equals(1L)).findFirst().orElseThrow().getQuantity());
        assertEquals(3, request.getValue().getTickets().stream()
                .filter(t -> t.getTicketTypeId().equals(2L)).findFirst().orElseThrow().getQuantity());
        Booking result = bookings.findById(booking.getId()).orElseThrow();
        assertEquals(BookingStatus.EXPIRED, result.getStatus());
        assertNull(result.getReservationExpiresAt());
    }

    @Test
    void nonExpiredAndUntimedReservationsAreUntouched() {
        Booking fresh = pending(LocalDateTime.now().plusMinutes(1));
        Booking legacy = pending(null);
        LocalDateTime now = LocalDateTime.now();
        expiry.expireReservation(fresh.getId(), now);
        expiry.expireReservation(legacy.getId(), now);
        assertFalse(bookings.findExpiredReservationIds(BookingStatus.PENDING, now, PageRequest.of(0, 50))
                .contains(fresh.getId()));
        verifyNoInteractions(events);
    }

    @Test
    void confirmedAndSuccessfullyPaidBookingsAreUntouched() {
        Booking confirmed = pending(LocalDateTime.now().minusMinutes(1));
        Booking paid = pending(LocalDateTime.now().minusMinutes(1));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Booking first = bookings.findById(confirmed.getId()).orElseThrow();
            first.setStatus(BookingStatus.CONFIRMED);
            Booking second = bookings.findById(paid.getId()).orElseThrow();
            second.setPayment(Payment.builder().method(PaymentMethod.CARD).amount(BigDecimal.TEN)
                    .status(PaymentStatus.SUCCESS).paidAt(LocalDateTime.now())
                    .transactionReference(UUID.randomUUID().toString()).build());
        });
        expiry.expireReservation(confirmed.getId(), LocalDateTime.now());
        expiry.expireReservation(paid.getId(), LocalDateTime.now());
        verifyNoInteractions(events);
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(confirmed.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.PENDING, bookings.findById(paid.getId()).orElseThrow().getStatus());
    }

    @Test
    void repeatedExpiryDoesNotReleaseTwice() {
        Booking booking = pending(LocalDateTime.now().minusMinutes(1));
        expiry.expireReservation(booking.getId(), LocalDateTime.now());
        expiry.expireReservation(booking.getId(), LocalDateTime.now());
        verify(events, times(1)).releaseTickets(eq(100L), any());
    }

    @Test
    void releaseFailureRollsBackAndLaterRetrySucceeds() {
        Booking booking = pending(LocalDateTime.now().minusMinutes(1));
        LocalDateTime persistedExpiry = bookings.findById(booking.getId()).orElseThrow().getReservationExpiresAt();
        doThrow(new IllegalStateException("Event Service unavailable")).doNothing()
                .when(events).releaseTickets(eq(100L), any());
        assertThrows(IllegalStateException.class,
                () -> expiry.expireReservation(booking.getId(), LocalDateTime.now()));
        Booking failed = bookings.findById(booking.getId()).orElseThrow();
        assertEquals(BookingStatus.PENDING, failed.getStatus());
        assertEquals(persistedExpiry, failed.getReservationExpiresAt());
        expiry.expireReservation(booking.getId(), LocalDateTime.now());
        assertEquals(BookingStatus.EXPIRED, bookings.findById(booking.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentWorkersReleaseOnlyOnce() throws Exception {
        Booking booking = pending(LocalDateTime.now().minusMinutes(1));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            expiry.expireReservation(booking.getId(), LocalDateTime.now());
            return null;
        };
        try {
            Future<Void> first = workers.submit(task);
            Future<Void> second = workers.submit(task);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            verify(events, times(1)).releaseTickets(eq(100L), any());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void failedImmediateCompensationCommitsReachableReservationWithConfiguredTimeout() {
        when(idempotency.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(idempotency.claim(anyString(), anyString(), anyString()))
                .thenReturn(IdempotencyRecord.builder().id(1L).build());
        when(events.getEventBookingInfo(100L)).thenReturn(EventBookingInfoResponse.builder()
                .eventId(100L).title("Test").status("PUBLISHED")
                .startDateTime(LocalDateTime.now().plusDays(1))
                .ticketTypes(List.of(EventTicketTypeResponse.builder().ticketTypeId(1L)
                        .ticketTypeName("Standard").price(BigDecimal.TEN).build())).build());
        doThrow(new IllegalStateException("Response lost")).when(events).releaseTickets(eq(100L), any());
        CreateBookingRequest request = CreateBookingRequest.builder().eventId(100L)
                .paymentMethod(PaymentMethod.SIMULATED_FAIL)
                .ticketSelections(List.of(TicketSelectionRequest.builder().ticketTypeId(1L).quantity(2).build()))
                .build();
        String key = UUID.randomUUID().toString();
        LocalDateTime before = LocalDateTime.now();
        assertThrows(PaymentFailedException.class,
                () -> bookingService.createBooking("user", key, "correlation", request));
        Booking booking = bookings.findByUserIdAndIdempotencyKey("user", key).orElseThrow();
        assertEquals(BookingStatus.PENDING, booking.getStatus());
        assertNotNull(booking.getReservationExpiresAt());
        assertFalse(booking.getReservationExpiresAt().isBefore(before.plusMinutes(2)));
        assertFalse(booking.getReservationExpiresAt().isAfter(LocalDateTime.now().plusMinutes(2)));
        assertEquals(PaymentStatus.FAILED, bookings.findById(booking.getId()).orElseThrow().getPayment().getStatus());
        expiry.expireReservation(booking.getId(), before.plusMinutes(1));
        verify(events, times(1)).releaseTickets(eq(100L), any());
        doNothing().when(events).releaseTickets(eq(100L), any());
        expiry.expireReservation(booking.getId(), LocalDateTime.now().plusMinutes(3));
        assertEquals(BookingStatus.EXPIRED, bookings.findById(booking.getId()).orElseThrow().getStatus());
    }

    private Booking pending(LocalDateTime expiresAt) {
        Booking booking = Booking.builder().bookingReference(UUID.randomUUID().toString().substring(0, 32))
                .userId("user").idempotencyKey(UUID.randomUUID().toString()).requestFingerprint("fingerprint")
                .eventId(100L).eventTitleSnapshot("Test").eventStartDateTimeSnapshot(LocalDateTime.now().plusDays(1))
                .status(BookingStatus.PENDING).totalAmount(BigDecimal.TEN).reservationExpiresAt(expiresAt).build();
        booking.addItem(BookingItem.builder().ticketTypeId(1L).ticketTypeNameSnapshot("Standard")
                .unitPrice(BigDecimal.ONE).quantity(2).subtotal(BigDecimal.valueOf(2)).build());
        booking.addItem(BookingItem.builder().ticketTypeId(2L).ticketTypeNameSnapshot("VIP")
                .unitPrice(BigDecimal.ONE).quantity(3).subtotal(BigDecimal.valueOf(3)).build());
        return bookings.saveAndFlush(booking);
    }
}
