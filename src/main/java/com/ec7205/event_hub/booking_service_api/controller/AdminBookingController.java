package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.config.AuthenticatedUser;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.AdminBookingPaginateResponseDto;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.service.AdminBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/booking-service/api/v1/admin")
public class AdminBookingController {

    private final AdminBookingService adminBookingService;

    @GetMapping("/all")
    public ResponseEntity<AdminBookingPaginateResponseDto> getAllBookings(
            Authentication authentication,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String userId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok(adminBookingService.getAllBookings(user.role(), status, eventId, userId, pageable));
    }

    @GetMapping("/stats")
    public ResponseEntity<BookingStatsResponse> getBookingStats(Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return ResponseEntity.ok(adminBookingService.getBookingStats(user.role()));
    }
}
