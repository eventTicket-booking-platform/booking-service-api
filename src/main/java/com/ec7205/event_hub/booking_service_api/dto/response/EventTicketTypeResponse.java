package com.ec7205.event_hub.booking_service_api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventTicketTypeResponse {

    private Long ticketTypeId;
    private String ticketTypeName;
    private BigDecimal price;
}
