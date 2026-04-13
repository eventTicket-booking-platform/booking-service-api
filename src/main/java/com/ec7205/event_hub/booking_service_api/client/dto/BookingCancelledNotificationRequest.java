package com.ec7205.event_hub.booking_service_api.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingCancelledNotificationRequest {
    private String userId;
    private String email;
    private String name;
    private String bookingId;
    private String eventTitle;
}
