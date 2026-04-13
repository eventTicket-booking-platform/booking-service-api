package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.client.dto.BookingCancelledNotificationRequest;
import com.ec7205.event_hub.booking_service_api.client.dto.BookingConfirmedNotificationRequest;
import com.ec7205.event_hub.booking_service_api.utils.StandardResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service-api")
public interface NotificationServiceClient {

    @PostMapping("/notification-service/api/v1/internal/notifications/booking-confirmed")
    StandardResponseDto sendBookingConfirmed(
            @RequestBody BookingConfirmedNotificationRequest request
    );

    @PostMapping("/notification-service/api/v1/internal/notifications/booking-cancelled")
    StandardResponseDto sendBookingCancelled(
            @RequestBody BookingCancelledNotificationRequest request
    );
}
