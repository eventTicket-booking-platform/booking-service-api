package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.dto.request.ReserveTicketsRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "event-service-api", url = "${services.event-service.url}")
public interface EventServiceClient {
    @GetMapping("/event-service/api/v1/events/admin/owned-ids")
    java.util.List<Long> getOwnedEventIds(@RequestHeader("Authorization") String authorizationHeader);

    @GetMapping("/event-service/api/v1/internal/events/{eventId}/booking-info")
    EventBookingInfoResponse getEventBookingInfo(@PathVariable("eventId") Long eventId);

    @PostMapping("/event-service/api/v1/internal/events/{eventId}/reserve")
    void reserveTickets(
            @PathVariable("eventId") Long eventId,
            @RequestBody ReserveTicketsRequest request
    );

    @PostMapping("/event-service/api/v1/internal/events/{eventId}/release")
    void releaseTickets(
            @PathVariable("eventId") Long eventId,
            @RequestBody ReserveTicketsRequest request
    );
}
