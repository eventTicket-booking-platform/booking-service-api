package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingCountResponse;
import com.ec7205.event_hub.booking_service_api.service.InternalBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/booking-service/api/v1/bookings/internal")
public class InternalBookingController {

    private final InternalBookingService internalBookingService;

    @GetMapping("/event/{eventId}/count")
    public ResponseEntity<EventBookingCountResponse> getConfirmedBookingCountByEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(internalBookingService.getConfirmedBookingCountByEvent(eventId));
    }
}
