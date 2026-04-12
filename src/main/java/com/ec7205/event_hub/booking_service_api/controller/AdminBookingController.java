package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.service.AdminBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/booking-service/api/v1/bookings/admin")
public class AdminBookingController {

    private final AdminBookingService adminBookingService;

    @GetMapping("/all")
    public ResponseEntity<Page<AdminBookingSummaryResponse>> getAllBookings(
            @RequestHeader("X-User-Role") String userRole,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Long userId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return ResponseEntity.ok(adminBookingService.getAllBookings(userRole, status, eventId, userId, pageable));
    }

    @GetMapping("/stats")
    public ResponseEntity<BookingStatsResponse> getBookingStats(
            @RequestHeader("X-User-Role") String userRole
    ) {
        return ResponseEntity.ok(adminBookingService.getBookingStats(userRole));
    }
}
