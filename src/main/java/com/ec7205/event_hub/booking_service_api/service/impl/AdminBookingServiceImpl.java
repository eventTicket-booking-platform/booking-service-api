package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.AuthServiceClient;
import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
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
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminBookingServiceImpl implements AdminBookingService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String HOST_ROLE = "HOST";

    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final AuthServiceClient authServiceClient;
    private final EventServiceClient eventServiceClient;

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

        List<Long> scope = eventScope(userRole, authorizationHeader, eventId);
        if (HOST_ROLE.equalsIgnoreCase(userRole) && userEmail != null && !userEmail.isBlank()) {
            throw new UnauthorizedActionException("User email lookup is restricted to admins");
        }

        String resolvedUserId = resolveUserIdFilter(authorizationHeader, userId, userEmail);
        Specification<Booking> specification = withinEvents(scope);

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
    public BookingStatsResponse getBookingStats(String userRole, String authorizationHeader, Long eventId) {
        assertAdminOrHost(userRole);

        List<Long> scope = eventScope(userRole, authorizationHeader, eventId);
        if (scope != null) {
            Specification<Booking> eligible = withinEvents(scope);
            return BookingStatsResponse.builder()
                    .totalBookings(bookingRepository.count(eligible))
                    .confirmedBookings(countStatus(eligible, BookingStatus.CONFIRMED))
                    .cancelledBookings(countStatus(eligible, BookingStatus.CANCELLED))
                    .pendingBookings(countStatus(eligible, BookingStatus.PENDING))
                    .totalRevenue(scope.isEmpty() ? BigDecimal.ZERO
                            : bookingRepository.sumTotalAmountByStatusAndEventIdIn(BookingStatus.CONFIRMED, scope))
                    .build();
        }

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

    private long countStatus(Specification<Booking> scope, BookingStatus status) {
        return bookingRepository.count(scope.and((root, query, cb) -> cb.equal(root.get("status"), status)));
    }

    private Specification<Booking> withinEvents(List<Long> scope) {
        return (root, query, cb) -> scope == null ? cb.conjunction()
                : scope.isEmpty() ? cb.disjunction() : root.get("eventId").in(scope);
    }

    private List<Long> eventScope(String role, String authorizationHeader, Long eventId) {
        if (ADMIN_ROLE.equalsIgnoreCase(role)) {
            return eventId == null ? null : List.of(eventId);
        }
        // Forward the caller's JWT; Event Service derives the owner from its subject.
        List<Long> owned = Objects.requireNonNull(eventServiceClient.getOwnedEventIds(authorizationHeader));
        if (eventId != null && !owned.contains(eventId)) {
            throw new UnauthorizedActionException("You do not own this event");
        }
        return eventId == null ? owned : List.of(eventId);
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
