package com.ec7205.event_hub.booking_service_api.dto.response.pagination;

import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BookingPaginateResponseDto {

    private List<BookingSummaryResponse> dataList;
    private long dataCount;
}
