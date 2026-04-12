package com.ec7205.event_hub.booking_service_api.dto.response;

import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailResponse {

    private Long bookingId;
    private String bookingReference;
    private Long userId;
    private Long eventId;
    private String eventTitle;
    private LocalDateTime eventStartDateTime;
    private BookingStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime bookingDate;
    private List<BookingItemResponse> items;
    private PaymentResponse payment;
}
