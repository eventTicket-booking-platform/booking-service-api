package com.ec7205.event_hub.booking_service_api.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqNotificationConfig {

    @Value("${eventhub.notification.booking-queue:booking.notification.queue}")
    private String bookingNotificationQueue;

    @Value("${eventhub.notification.booking-retry-queue:booking.notification.retry.queue}")
    private String bookingNotificationRetryQueue;

    @Value("${eventhub.notification.booking-dlq:booking.notification.dlq}")
    private String bookingNotificationDlq;

    @Bean
    public Queue bookingNotificationQueue() {
        return QueueBuilder
                .durable(bookingNotificationQueue)
                .build();
    }

    @Bean
    public Queue bookingNotificationRetryQueue() {
        return QueueBuilder
                .durable(bookingNotificationRetryQueue)
                .withArgument("x-message-ttl", 10000)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument(
                        "x-dead-letter-routing-key",
                        bookingNotificationQueue
                )
                .build();
    }

    @Bean
    public Queue bookingNotificationDlq() {
        return QueueBuilder
                .durable(bookingNotificationDlq)
                .build();
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
