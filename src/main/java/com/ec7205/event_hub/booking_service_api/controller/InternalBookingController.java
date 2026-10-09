package com.ec7205.event_hub.booking_service_api.controller;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingCountResponse;
import com.ec7205.event_hub.booking_service_api.service.InternalBookingService;
import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.config.AuthenticatedUser;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final EventServiceClient eventServiceClient;

    @GetMapping("/event/{eventId}/count")
    @PreAuthorize("hasAnyAuthority('admin','host')")
    public ResponseEntity<EventBookingCountResponse> getConfirmedBookingCountByEvent(@PathVariable Long eventId,
                                                                                     Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        if (!"ADMIN".equals(user.role())) {
            var event = eventServiceClient.getEventBookingInfo(eventId);
            if (user.userId() == null || event == null || !user.userId().equals(event.getCreatedBy())) {
                throw new UnauthorizedActionException("You do not own this event");
            }
        }
        return ResponseEntity.ok(internalBookingService.getConfirmedBookingCountByEvent(eventId));
    }
}
