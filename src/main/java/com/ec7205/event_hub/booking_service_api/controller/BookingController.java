package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.config.AuthenticatedUser;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.BookingPaginateResponseDto;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/booking-service/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(
                    value = "X-Correlation-ID",
                    required = false
            ) String correlationId,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);

        String effectiveCorrelationId =
                resolveCorrelationId(correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        bookingService.createBooking(
                                user.userId(),
                                idempotencyKey,
                                effectiveCorrelationId,
                                request
                        )
                );
    }

    @GetMapping("/my")
    public ResponseEntity<BookingPaginateResponseDto> getMyBookings(
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

    private String resolveCorrelationId(String correlationId) {

        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }

        if (!correlationId.matches("^[A-Za-z0-9._-]{1,100}$")) {
            throw new BadRequestException(
                    "Invalid X-Correlation-ID header"
            );
        }

        return correlationId;
    }
}
