package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.service.AdminBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AdminBookingServiceImpl implements AdminBookingService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminBookingSummaryResponse> getAllBookings(
            String userRole,
            BookingStatus status,
            Long eventId,
            Long userId,
            Pageable pageable
    ) {
        assertAdmin(userRole);

        Specification<Booking> specification = Specification.where(null);

        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (eventId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("eventId"), eventId));
        }
        if (userId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }

        return bookingRepository.findAll(specification, pageable)
                .map(bookingMapper::toAdminBookingSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponse getBookingStats(String userRole) {
        assertAdmin(userRole);

        long totalBookings = bookingRepository.count();
        long confirmedBookings = bookingRepository.countByStatus(BookingStatus.CONFIRMED);
        long cancelledBookings = bookingRepository.countByStatus(BookingStatus.CANCELLED);
        long failedBookings = bookingRepository.countByStatus(BookingStatus.FAILED);
        BigDecimal totalRevenue = bookingRepository.sumTotalAmountByStatus(BookingStatus.CONFIRMED);

        return BookingStatsResponse.builder()
                .totalBookings(totalBookings)
                .confirmedBookings(confirmedBookings)
                .cancelledBookings(cancelledBookings)
                .failedBookings(failedBookings)
                .totalRevenue(totalRevenue)
                .build();
    }

    private void assertAdmin(String userRole) {
        if (!ADMIN_ROLE.equalsIgnoreCase(userRole)) {
            throw new UnauthorizedActionException("Admin access is required for this operation");
        }
    }
}
