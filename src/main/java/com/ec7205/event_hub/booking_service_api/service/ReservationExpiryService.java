package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.request.ReserveTicketsRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketReservationRequest;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.PaymentStatus;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationExpiryService {
    private final BookingRepository bookingRepository;
    private final EventServiceClient eventServiceClient;

    @Transactional
    public void expireReservation(Long bookingId, LocalDateTime now) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING
                || booking.getReservationExpiresAt() == null
                || booking.getReservationExpiresAt().isAfter(now)) {
            return;
        }
        // Check payment independently of booking status to protect inconsistent rows.
        if (booking.getPayment() != null
                && (booking.getPayment().getStatus() == PaymentStatus.SUCCESS
                    || booking.getPayment().getStatus() == PaymentStatus.REFUNDED
                    || booking.getPayment().getPaidAt() != null)) {
            return;
        }

        eventServiceClient.releaseTickets(booking.getEventId(), ReserveTicketsRequest.builder()
                .releaseId(booking.getBookingReference())
                .tickets(booking.getItems().stream().map(item -> TicketReservationRequest.builder()
                        .ticketTypeId(item.getTicketTypeId())
                        .quantity(item.getQuantity())
                        .build()).toList())
                .build());

        // Exceptions leave the row eligible for a later poll. The remote release ID
        // also makes a retry safe after a lost response or a local commit failure.
        booking.setStatus(BookingStatus.EXPIRED);
        booking.setReservationExpiresAt(null);
        bookingRepository.save(booking);
    }
}
