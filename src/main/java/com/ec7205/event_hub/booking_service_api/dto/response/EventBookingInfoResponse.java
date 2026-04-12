package com.ec7205.event_hub.booking_service_api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventBookingInfoResponse {

    private Long eventId;
    private String title;
    private String status;
    private LocalDateTime startDateTime;
    private List<EventTicketTypeResponse> ticketTypes;
}
