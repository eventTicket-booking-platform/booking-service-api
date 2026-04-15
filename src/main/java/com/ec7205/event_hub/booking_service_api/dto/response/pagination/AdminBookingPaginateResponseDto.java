package com.ec7205.event_hub.booking_service_api.dto.response.pagination;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
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
public class AdminBookingPaginateResponseDto {

    private List<AdminBookingSummaryResponse> dataList;
    private long dataCount;
}
