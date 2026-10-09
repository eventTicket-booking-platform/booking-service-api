package com.ec7205.event_hub.booking_service_api.integration;

import com.ec7205.event_hub.booking_service_api.config.RabbitMqNotificationConfig;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("integration")
@SpringJUnitConfig(BookingNotificationPublisherRabbitIT.RabbitConfiguration.class)
class BookingNotificationPublisherRabbitIT {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitMqNotificationConfig.class, BookingNotificationEventPublisher.class})
    static class RabbitConfiguration {
    }

    @Autowired private BookingNotificationEventPublisher publisher;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;
    @Autowired private CachingConnectionFactory connectionFactory;

    @Test
    void publisherReceivesBrokerConfirmAndRoutesJsonToBookingQueue() throws Exception {
        admin.initialize();
        assertTrue(connectionFactory.isPublisherConfirms());
        BookingNotificationEvent event = BookingNotificationEvent.builder()
                .eventId("outbox-42").type("BOOKING_CONFIRMED").correlationId("correlation-42")
                .payload(Map.of("bookingReference", "BK-42")).build();

        // publish itself waits for the correlated broker ACK; timeout/NACK fails this assertion.
        assertDoesNotThrow(() -> publisher.publish(event));

        Message message = rabbit.receive("booking.notification.queue", 5000);
        assertNotNull(message, "Confirmed event must arrive in the production booking queue");
        assertEquals("application/json", message.getMessageProperties().getContentType());
        JsonNode json = new ObjectMapper().readTree(message.getBody());
        assertEquals(event.getEventId(), json.path("eventId").asText());
        assertEquals(event.getType(), json.path("type").asText());
        assertEquals(event.getCorrelationId(), json.path("correlationId").asText());
        assertEquals("BK-42", json.path("payload").path("bookingReference").asText());
        assertNull(rabbit.receive("booking.notification.queue"));
    }
}
