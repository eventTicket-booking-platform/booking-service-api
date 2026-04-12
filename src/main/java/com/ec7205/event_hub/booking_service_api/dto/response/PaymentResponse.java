package com.ec7205.event_hub.booking_service_api.dto.response;

import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import com.ec7205.event_hub.booking_service_api.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private PaymentMethod method;
    private BigDecimal amount;
    private PaymentStatus status;
    private String transactionReference;
    private LocalDateTime paidAt;
}
