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
public class BookingStatsResponse {

    private long totalBookings;
    private long confirmedBookings;
    private long cancelledBookings;
    private long pendingBookings;
    private BigDecimal totalRevenue;
}
