package com.ec7205.event_hub.booking_service_api.dto.request;

import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingRequest {

    @NotNull(message = "Event id is required")
    private Long eventId;

    @Valid
    @NotEmpty(message = "At least one ticket selection is required")
    private List<TicketSelectionRequest> ticketSelections;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;
}
