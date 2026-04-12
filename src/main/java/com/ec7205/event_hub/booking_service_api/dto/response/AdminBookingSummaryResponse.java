package com.ec7205.event_hub.booking_service_api.dto.response;

import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
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
public class AdminBookingSummaryResponse {

    private Long bookingId;
    private String bookingReference;
    private Long userId;
    private Long eventId;
    private String eventTitle;
    private BookingStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime bookingDate;
}
