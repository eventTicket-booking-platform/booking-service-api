package com.ec7205.event_hub.booking_service_api.dto.request;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketReservationRequest {
    private Long ticketTypeId;
    private Integer quantity;
}
