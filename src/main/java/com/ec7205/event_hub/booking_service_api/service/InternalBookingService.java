package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingCountResponse;

public interface InternalBookingService {

    EventBookingCountResponse getConfirmedBookingCountByEvent(Long eventId);
}
