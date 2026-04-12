package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class EventServiceRestClient implements EventServiceClient {

    private final RestClient restClient;

    public EventServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.event-service.base-url:http://event-service}") String eventServiceBaseUrl
    ) {
        this.restClient = restClientBuilder
                .baseUrl(eventServiceBaseUrl)
                .build();
    }

    @Override
    public EventBookingInfoResponse getEventBookingInfo(Long eventId) {
        try {
            EventBookingInfoResponse response = restClient.get()
                    .uri("/internal/events/{eventId}/booking-info", eventId)
                    .retrieve()
                    .body(EventBookingInfoResponse.class);

            if (response == null) {
                throw new ResourceNotFoundException("Event not found for id: " + eventId);
            }

            return response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new ResourceNotFoundException("Event not found for id: " + eventId);
            }
            if (ex.getStatusCode().is4xxClientError()) {
                throw new BadRequestException("Unable to validate event information for event id: " + eventId);
            }
            throw new ConflictException("Event service responded with an error while validating event id: " + eventId);
        } catch (RestClientException ex) {
            throw new ConflictException("Event service is unavailable for booking validation at the moment");
        }
    }
}
