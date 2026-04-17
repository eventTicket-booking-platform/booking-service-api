package com.ec7205.event_hub.booking_service_api.messaging;

import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingNotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${eventhub.notification.booking-queue:booking.notification.queue}")
    private String bookingNotificationQueue;

    public void publish(BookingNotificationEvent event) {
        rabbitTemplate.convertAndSend(bookingNotificationQueue, event);
    }
}
