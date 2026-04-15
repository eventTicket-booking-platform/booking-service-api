package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.ApiMessageResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.BookingPaginateResponseDto;
import org.springframework.data.domain.Pageable;

public interface BookingService {

    CreateBookingResponse createBooking(String userId, CreateBookingRequest request);

    BookingPaginateResponseDto getMyBookings(String userId, Pageable pageable);

    BookingDetailResponse getBookingDetails(Long bookingId, String userId, String userRole);

    ApiMessageResponse cancelBooking(Long bookingId, String userId, String userRole);
}
