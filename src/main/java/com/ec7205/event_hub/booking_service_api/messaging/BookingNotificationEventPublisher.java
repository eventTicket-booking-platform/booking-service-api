package com.ec7205.event_hub.booking_service_api.messaging;

import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class BookingNotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${eventhub.notification.booking-queue:booking.notification.queue}")
    private String bookingNotificationQueue;

    public void publish(BookingNotificationEvent event) {

        CorrelationData correlationData =
                new CorrelationData(event.getEventId());

        rabbitTemplate.convertAndSend(
                "",
                bookingNotificationQueue,
                event,
                correlationData
        );

        try {
            CorrelationData.Confirm confirm =
                    correlationData
                            .getFuture()
                            .get(5, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                throw new IllegalStateException(
                        "RabbitMQ rejected event " +
                                event.getEventId() +
                                ": " +
                                confirm.getReason()
                );
            }

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "RabbitMQ publish confirmation failed for event " +
                            event.getEventId(),
                    ex
            );
        }
    }
}
