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
public class GeneralAlertNotificationRequest {
    private String userId;
    private String email;
    private String subject;
    private String message;
}
