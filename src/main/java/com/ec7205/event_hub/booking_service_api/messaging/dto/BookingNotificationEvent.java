package com.ec7205.event_hub.booking_service_api.messaging.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingNotificationEvent {
    private String type;
    private Map<String, Object> payload;
}
