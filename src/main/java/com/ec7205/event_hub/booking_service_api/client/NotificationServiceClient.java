package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.client.dto.GeneralAlertNotificationRequest;
import com.ec7205.event_hub.booking_service_api.utils.StandardResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service-api")
public interface NotificationServiceClient {

    @PostMapping("/notification-service/api/v1/internal/notifications/general-alert")
    StandardResponseDto sendGeneralAlert(@RequestBody GeneralAlertNotificationRequest request);
}
