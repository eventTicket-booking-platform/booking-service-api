package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.AuthServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingStatsResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.AdminBookingPaginateResponseDto;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
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
    private static final String HOST_ROLE = "HOST";

    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final AuthServiceClient authServiceClient;

    @Override
    @Transactional(readOnly = true)
    public AdminBookingPaginateResponseDto getAllBookings(
            String userRole,
            String authorizationHeader,
            BookingStatus status,
            Long eventId,
            String userId,
            String userEmail,
            Pageable pageable
    ) {
        assertAdminOrHost(userRole);

        String resolvedUserId = resolveUserIdFilter(authorizationHeader, userId, userEmail);
        Specification<Booking> specification = Specification.where(null);

        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (eventId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("eventId"), eventId));
        }
        if (resolvedUserId != null && !resolvedUserId.isBlank()) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("userId"), resolvedUserId));
        }

        Page<AdminBookingSummaryResponse> bookingPage = bookingRepository.findAll(specification, pageable)
                .map(bookingMapper::toAdminBookingSummaryResponse);

        return AdminBookingPaginateResponseDto.builder()
                .dataList(bookingPage.getContent())
                .dataCount(bookingPage.getTotalElements())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponse getBookingStats(String userRole) {
        assertAdminOrHost(userRole);

        long totalBookings = bookingRepository.count();
        long confirmedBookings = bookingRepository.countByStatus(BookingStatus.CONFIRMED);
        long cancelledBookings = bookingRepository.countByStatus(BookingStatus.CANCELLED);
        long pendingBookings = bookingRepository.countByStatus(BookingStatus.PENDING);
        BigDecimal totalRevenue = bookingRepository.sumTotalAmountByStatus(BookingStatus.CONFIRMED);

        return BookingStatsResponse.builder()
                .totalBookings(totalBookings)
                .confirmedBookings(confirmedBookings)
                .cancelledBookings(cancelledBookings)
                .pendingBookings(pendingBookings)
                .totalRevenue(totalRevenue)
                .build();
    }

    private void assertAdminOrHost(String userRole) {
        if (!ADMIN_ROLE.equalsIgnoreCase(userRole) && !HOST_ROLE.equalsIgnoreCase(userRole)) {
            throw new UnauthorizedActionException("Admin or host access is required for this operation");
        }
    }

    private String resolveUserIdFilter(String authorizationHeader, String userId, String userEmail) {
        String normalizedUserId = userId == null ? null : userId.trim();
        String normalizedUserEmail = userEmail == null ? null : userEmail.trim();

        if (normalizedUserEmail == null || normalizedUserEmail.isBlank()) {
            return normalizedUserId;
        }

        Object resolved = authServiceClient.resolveUserIdByEmail(authorizationHeader, normalizedUserEmail).getData();
        String resolvedUserId = resolved == null ? null : resolved.toString().trim();
        if (resolvedUserId == null || resolvedUserId.isBlank()) {
            throw new BadRequestException("Unable to resolve user id for provided email");
        }

        if (normalizedUserId != null && !normalizedUserId.isBlank() && !normalizedUserId.equals(resolvedUserId)) {
            throw new BadRequestException("Provided userId does not match provided userEmail");
        }

        return resolvedUserId;
    }
}
