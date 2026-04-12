package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.ApiMessageResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BookingService {

    CreateBookingResponse createBooking(Long userId, CreateBookingRequest request);

    Page<BookingSummaryResponse> getMyBookings(Long userId, Pageable pageable);

    BookingDetailResponse getBookingDetails(Long bookingId, Long userId, String userRole);

    ApiMessageResponse cancelBooking(Long bookingId, Long userId, String userRole);
}
