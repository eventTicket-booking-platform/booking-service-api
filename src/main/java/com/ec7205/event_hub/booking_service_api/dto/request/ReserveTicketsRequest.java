package com.ec7205.event_hub.booking_service_api.dto.request;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReserveTicketsRequest {
    private List<TicketReservationRequest> tickets;
}
