package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminBookingService {

    Page<AdminBookingSummaryResponse> getAllBookings(
            String userRole,
            BookingStatus status,
            Long eventId,
            String userId,
            Pageable pageable
    );

    BookingStatsResponse getBookingStats(String userRole);
}
