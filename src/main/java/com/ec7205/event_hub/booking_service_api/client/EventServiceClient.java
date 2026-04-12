package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;

public interface EventServiceClient {

    EventBookingInfoResponse getEventBookingInfo(Long eventId);
}
