package com.ec7205.event_hub.booking_service_api.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqNotificationConfig {

    @Value("${eventhub.notification.booking-queue:booking.notification.queue}")
    private String bookingNotificationQueue;

    @Bean
    public Queue bookingNotificationQueue() {
        return new Queue(bookingNotificationQueue, true);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
