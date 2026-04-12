package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingCountResponse;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.service.InternalBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InternalBookingServiceImpl implements InternalBookingService {

    private final BookingRepository bookingRepository;

    @Override
    @Transactional(readOnly = true)
    public EventBookingCountResponse getConfirmedBookingCountByEvent(Long eventId) {
        long count = bookingRepository.countByEventIdAndStatus(eventId, BookingStatus.CONFIRMED);
        return EventBookingCountResponse.builder()
                .eventId(eventId)
                .confirmedBookingCount(count)
                .build();
    }
}
