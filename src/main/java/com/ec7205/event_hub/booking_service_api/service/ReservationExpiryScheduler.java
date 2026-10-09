package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryScheduler {
    private final BookingRepository bookingRepository;
    private final ReservationExpiryService expiryService;

    @Scheduled(fixedDelayString = "${eventhub.booking.reservation-expiry-poll-ms:30000}")
    public void expireReservations() {
        LocalDateTime now = LocalDateTime.now();
        for (Long id : bookingRepository.findExpiredReservationIds(
                BookingStatus.PENDING, now, PageRequest.of(0, 50))) {
            try {
                expiryService.expireReservation(id, now);
            } catch (Exception failure) {
                log.warn("Reservation expiry will retry booking {}: {}", id, failure.getMessage());
            }
        }
    }
}
