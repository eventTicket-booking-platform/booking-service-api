package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.config.AuthenticatedUser;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.ApiMessageResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/booking-service/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            Authentication authentication,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(user.userId(), request));
    }

    @GetMapping("/my")
    public ResponseEntity<Page<BookingSummaryResponse>> getMyBookings(
            Authentication authentication,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok(bookingService.getMyBookings(user.userId(), pageable));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingDetailResponse> getBookingDetails(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok(bookingService.getBookingDetails(bookingId, user.userId(), user.role()));
    }

    @PatchMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiMessageResponse> cancelBooking(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId, user.userId(), user.role()));
    }
}
