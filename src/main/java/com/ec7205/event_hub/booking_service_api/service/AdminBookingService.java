package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.AdminBookingPaginateResponseDto;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import org.springframework.data.domain.Pageable;

public interface AdminBookingService {

    AdminBookingPaginateResponseDto getAllBookings(
            String userRole,
            String authorizationHeader,
            BookingStatus status,
            Long eventId,
            String userId,
            String userEmail,
            Pageable pageable
    );

    BookingStatsResponse getBookingStats(String userRole);
}
