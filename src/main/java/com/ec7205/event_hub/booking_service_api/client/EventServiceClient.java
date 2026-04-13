package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "event-service-api")
public interface EventServiceClient {
    @GetMapping("/api/v1/internal/events/{eventId}/booking-info")
    EventBookingInfoResponse getEventBookingInfo(@PathVariable("eventId") Long eventId);
}
