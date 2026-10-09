package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.client.dto.BookingConfirmedNotificationRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.ReserveTicketsRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketReservationRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketSelectionRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventTicketTypeResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.pagination.BookingPaginateResponseDto;
import com.ec7205.event_hub.booking_service_api.entity.*;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.IdempotencyStatus;
import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import com.ec7205.event_hub.booking_service_api.enums.PaymentStatus;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.exception.ResourceNotFoundException;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.messaging.dto.BookingNotificationEvent;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.repository.OutboxEventRepository;
import com.ec7205.event_hub.booking_service_api.repository.PaymentRepository;
import com.ec7205.event_hub.booking_service_api.service.BookingService;
import com.ec7205.event_hub.booking_service_api.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String HOST_ROLE = "HOST";
    private static final String PUBLISHED_EVENT_STATUS = "PUBLISHED";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final EventServiceClient eventServiceClient;
    private final BookingNotificationEventPublisher bookingNotificationEventPublisher;
    private final BookingMapper bookingMapper;
    private final IdempotencyService idempotencyService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${eventhub.booking.reservation-timeout:PT10M}")
    private Duration reservationTimeout = Duration.ofMinutes(10);

    @Override
    @Transactional(noRollbackFor = PaymentFailedException.class)
    public CreateBookingResponse createBooking(
            String userId,
            String idempotencyKey,
            String correlationId,
            CreateBookingRequest request
    ) {
        validateCreateRequest(userId, request);
        if (reservationTimeout.isZero() || reservationTimeout.isNegative()) {
            throw new IllegalStateException("Reservation timeout must be positive");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException(
                    "Idempotency-Key header is required"
            );
        }

        String requestFingerprint =
                generateRequestFingerprint(request);

        Optional<IdempotencyRecord> existingRecord =
                idempotencyService.find(
                        userId,
                        idempotencyKey
                );

        if (existingRecord.isPresent()) {

            IdempotencyRecord existing =
                    existingRecord.get();

            if (!Objects.equals(
                    existing.getRequestFingerprint(),
                    requestFingerprint
            )) {
                throw new ConflictException(
                        "Idempotency key was already used with a different request"
                );
            }

            if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                throw new ConflictException(
                        "A request with this idempotency key is already being processed"
                );
            }

            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {

                Booking booking =
                        bookingRepository.findById(
                                existing.getBookingId()
                        ).orElseThrow(
                                () -> new ResourceNotFoundException(
                                        "Booking linked to idempotency record was not found"
                                )
                        );

                return bookingMapper
                        .toCreateBookingResponse(booking);
            }

            if (existing.getStatus() == IdempotencyStatus.FAILED) {
                throw new ConflictException(
                        "Previous request with this idempotency key failed"
                );
            }
        }

        IdempotencyRecord record;

        try {
            record = idempotencyService.claim(
                    userId,
                    idempotencyKey,
                    requestFingerprint
            );
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "A request with this idempotency key is already being processed"
            );
        }

        try {

            EventBookingInfoResponse eventInfo =
                    eventServiceClient
                            .getEventBookingInfo(
                                    request.getEventId()
                            );

            validateEventForBooking(eventInfo);

            Map<Long, EventTicketTypeResponse> ticketTypeMap =
                    validateAndMapTicketTypes(
                            request,
                            eventInfo
                    );

            Booking booking =
                    initializeBooking(
                            userId,
                            idempotencyKey,
                            requestFingerprint,
                            eventInfo
                    );

            reserveRequestedTickets(request);

            BigDecimal totalAmount =
                    BigDecimal.ZERO;

            for (TicketSelectionRequest selection
                    : request.getTicketSelections()) {

                EventTicketTypeResponse ticketType =
                        ticketTypeMap.get(
                                selection.getTicketTypeId()
                        );

                BigDecimal subtotal =
                        ticketType.getPrice()
                                .multiply(
                                        BigDecimal.valueOf(
                                                selection.getQuantity()
                                        )
                                );

                BookingItem item =
                        BookingItem.builder()
                                .ticketTypeId(
                                        ticketType.getTicketTypeId()
                                )
                                .ticketTypeNameSnapshot(
                                        ticketType.getTicketTypeName()
                                )
                                .unitPrice(
                                        ticketType.getPrice()
                                )
                                .quantity(
                                        selection.getQuantity()
                                )
                                .subtotal(subtotal)
                                .build();

                booking.addItem(item);
                totalAmount =
                        totalAmount.add(subtotal);
            }

            booking.setTotalAmount(totalAmount);

            Booking savedBooking =
                    bookingRepository.save(booking);

            Payment payment =
                    simulatePayment(
                            savedBooking,
                            request.getPaymentMethod(),
                            totalAmount
                    );

            paymentRepository.save(payment);

            if (payment.getStatus() == PaymentStatus.FAILED) {
                savedBooking.setPayment(payment);
                savedBooking.setReservationExpiresAt(LocalDateTime.now().plus(reservationTimeout));

                try {
                    releaseRequestedTickets(request, savedBooking.getBookingReference());
                    savedBooking.setStatus(BookingStatus.CANCELLED);
                    savedBooking.setReservationExpiresAt(null);
                } catch (Exception releaseFailure) {
                    // Commit the unpaid booking via PaymentFailedException's noRollbackFor
                    // so the scheduler can retry compensation using the same release ID.
                    log.warn("Inventory release pending for booking {}: {}",
                            savedBooking.getBookingReference(), releaseFailure.getMessage());
                }

                bookingRepository.save(savedBooking);

                throw new PaymentFailedException(
                        "Payment failed for booking reference: "
                                + savedBooking.getBookingReference()
                );
            }

            savedBooking.setStatus(
                    BookingStatus.CONFIRMED
            );

            savedBooking.setPayment(payment);

            Booking confirmedBooking =
                    bookingRepository.save(
                            savedBooking
                    );

            idempotencyService.markCompleted(
                    record.getId(),
                    confirmedBooking.getId()
            );

            createBookingConfirmedOutboxEvent(
                    confirmedBooking,
                    correlationId
            );

            return bookingMapper
                    .toCreateBookingResponse(
                            confirmedBooking
                    );

        } catch (Exception ex) {

            idempotencyService.markFailed(
                    record.getId()
            );

            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BookingPaginateResponseDto getMyBookings(String userId, Pageable pageable) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("Authenticated user id is required");
        }

        Page<Booking> bookingPage = bookingRepository.findByUserId(userId, pageable);
        Map<Long, String> bannerCache = new HashMap<>();
        List<BookingSummaryResponse> summaries = bookingPage.getContent().stream()
                .peek(booking -> populateBannerIfMissing(booking, bannerCache))
                .map(bookingMapper::toBookingSummaryResponse)
                .toList();

        return BookingPaginateResponseDto.builder()
                .dataList(summaries)
                .dataCount(bookingPage.getTotalElements())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetails(Long bookingId, String userId, String userRole) {
        Booking booking = getBookingWithRelations(bookingId);
        assertOwnerOrAdminOrHost(booking, userId, userRole);
        populateBannerIfMissing(booking, new HashMap<>());
        return bookingMapper.toBookingDetailResponse(booking);
    }

    private void validateCreateRequest(
            String userId,
            CreateBookingRequest request
    ) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException(
                    "Authenticated user id is required"
            );
        }

        if (request == null) {
            throw new BadRequestException(
                    "Booking request is required"
            );
        }

        if (request.getTicketSelections() == null
                || request.getTicketSelections().isEmpty()) {
            throw new BadRequestException(
                    "At least one ticket selection is required"
            );
        }

        Set<Long> seenTicketTypeIds = new HashSet<>();

        for (TicketSelectionRequest selection
                : request.getTicketSelections()) {

            if (selection.getTicketTypeId() == null) {
                throw new BadRequestException(
                        "Ticket type id is required"
                );
            }

            if (selection.getQuantity() == null
                    || selection.getQuantity() <= 0) {
                throw new BadRequestException(
                        "Each ticket quantity must be greater than zero"
                );
            }

            if (!seenTicketTypeIds.add(
                    selection.getTicketTypeId()
            )) {
                throw new BadRequestException(
                        "Duplicate ticket type selections are not allowed"
                );
            }
        }
    }

    private void validateEventForBooking(EventBookingInfoResponse eventInfo) {
        if (eventInfo == null) {
            throw new ResourceNotFoundException("Event details were not found");
        }
        if (!PUBLISHED_EVENT_STATUS.equalsIgnoreCase(eventInfo.getStatus())) {
            throw new ConflictException("Bookings are allowed only for published events");
        }
        if (eventInfo.getStartDateTime() == null || !eventInfo.getStartDateTime().isAfter(LocalDateTime.now())) {
            throw new ConflictException("Bookings are allowed only for future events");
        }
    }

    private Map<Long, EventTicketTypeResponse> validateAndMapTicketTypes(
            CreateBookingRequest request,
            EventBookingInfoResponse eventInfo
    ) {

        if (eventInfo.getTicketTypes() == null
                || eventInfo.getTicketTypes().isEmpty()) {
            throw new ConflictException(
                    "No ticket types are available for this event"
            );
        }

        Map<Long, EventTicketTypeResponse> ticketTypeMap =
                new HashMap<>();

        for (EventTicketTypeResponse ticketType
                : eventInfo.getTicketTypes()) {
            ticketTypeMap.put(
                    ticketType.getTicketTypeId(),
                    ticketType
            );
        }

        for (TicketSelectionRequest selection
                : request.getTicketSelections()) {

            if (!ticketTypeMap.containsKey(
                    selection.getTicketTypeId()
            )) {
                throw new BadRequestException(
                        "Invalid ticket type requested: "
                                + selection.getTicketTypeId()
                );
            }
        }

        return ticketTypeMap;
    }

    private Booking initializeBooking(String userId,String idempotencyKey,String requestFingerprint, EventBookingInfoResponse eventInfo) {
        return Booking.builder()
                .bookingReference(generateBookingReference())
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(requestFingerprint)
                .userId(userId)
                .eventId(eventInfo.getEventId())
                .eventTitleSnapshot(eventInfo.getTitle())
                .eventBannerResourceUrlSnapshot(eventInfo.getBannerUrl())
                .eventStartDateTimeSnapshot(eventInfo.getStartDateTime())
                .status(BookingStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();
    }
    private void reserveRequestedTickets(CreateBookingRequest request) {

        List<TicketReservationRequest> tickets =
                request.getTicketSelections().stream()
                        .map(selection ->
                                TicketReservationRequest.builder()
                                        .ticketTypeId(selection.getTicketTypeId())
                                        .quantity(selection.getQuantity())
                                        .build()
                        )
                        .toList();

        ReserveTicketsRequest reserveRequest =
                ReserveTicketsRequest.builder()
                        .tickets(tickets)
                        .build();

        eventServiceClient.reserveTickets(
                request.getEventId(),
                reserveRequest
        );
    }

    private void releaseRequestedTickets(CreateBookingRequest request, String releaseId) {

        List<TicketReservationRequest> tickets =
                request.getTicketSelections().stream()
                        .map(selection ->
                                TicketReservationRequest.builder()
                                        .ticketTypeId(selection.getTicketTypeId())
                                        .quantity(selection.getQuantity())
                                        .build()
                        )
                        .toList();

        ReserveTicketsRequest releaseRequest =
                ReserveTicketsRequest.builder()
                        .releaseId(releaseId)
                        .tickets(tickets)
                        .build();

        eventServiceClient.releaseTickets(
                request.getEventId(),
                releaseRequest
        );
    }

    private Payment simulatePayment(Booking booking, PaymentMethod paymentMethod, BigDecimal amount) {
        PaymentStatus paymentStatus = paymentMethod == PaymentMethod.SIMULATED_FAIL
                ? PaymentStatus.FAILED
                : PaymentStatus.SUCCESS;

        Payment payment = Payment.builder()
                .booking(booking)
                .method(paymentMethod)
                .amount(amount)
                .status(paymentStatus)
                .transactionReference(generateTransactionReference())
                .paidAt(paymentStatus == PaymentStatus.SUCCESS ? LocalDateTime.now() : null)
                .build();

        booking.setPayment(payment);
        return payment;
    }

    private Booking getBookingWithRelations(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for id: " + bookingId));
    }

    private void populateBannerIfMissing(Booking booking, Map<Long, String> bannerCache) {
        if (booking.getEventBannerResourceUrlSnapshot() != null && !booking.getEventBannerResourceUrlSnapshot().isBlank()) {
            return;
        }

        String cached = bannerCache.get(booking.getEventId());
        if (cached != null) {
            booking.setEventBannerResourceUrlSnapshot(cached);
            return;
        }

        try {
            EventBookingInfoResponse eventInfo = eventServiceClient.getEventBookingInfo(booking.getEventId());
            String bannerUrl = eventInfo != null ? eventInfo.getBannerUrl() : null;
            if (bannerUrl != null && !bannerUrl.isBlank()) {
                booking.setEventBannerResourceUrlSnapshot(bannerUrl);
                bannerCache.put(booking.getEventId(), bannerUrl);
            }
        } catch (Exception ex) {
            log.warn("Unable to resolve banner URL for booking {} / event {}: {}", booking.getBookingReference(), booking.getEventId(), ex.getMessage());
        }
    }

    private void assertOwnerOrAdmin(Booking booking, String userId, String userRole) {
        if (isAdmin(userRole)) {
            return;
        }

        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("Authenticated user id is required");
        }

        if (!booking.getUserId().equals(userId)) {
            throw new UnauthorizedActionException("You are not allowed to access this booking");
        }
    }

    private void assertOwnerOrAdminOrHost(Booking booking, String userId, String userRole) {
        if (isAdminOrHost(userRole)) {
            return;
        }
        assertOwnerOrAdmin(booking, userId, userRole);
    }

    private boolean isAdmin(String userRole) {
        return ADMIN_ROLE.equalsIgnoreCase(userRole);
    }

    private boolean isAdminOrHost(String userRole) {
        return isAdmin(userRole) || HOST_ROLE.equalsIgnoreCase(userRole);
    }

    private Jwt getCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        return null;
    }

    private String resolveEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        return jwt.getClaimAsString("preferred_username");
    }

    private String resolveDisplayName(Jwt jwt) {
        String fullName = jwt.getClaimAsString("name");
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        String givenName = jwt.getClaimAsString("given_name");
        if (givenName != null && !givenName.isBlank()) {
            return givenName;
        }
        return null;
    }

    private String generateBookingReference() {
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return "BK-" + datePart + "-" + randomPart;
    }

    private String generateTransactionReference() {
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "TXN-" + randomPart;
    }
    private String generateRequestFingerprint(CreateBookingRequest request) {
        String raw = request.getEventId()
                + "|" + request.getPaymentMethod()
                + "|" + request.getTicketSelections().stream()
                .sorted(Comparator.comparing(TicketSelectionRequest::getTicketTypeId))
                .map(t -> t.getTicketTypeId() + ":" + t.getQuantity())
                .collect(Collectors.joining(","));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void createBookingConfirmedOutboxEvent(
            Booking booking,String correlationId
    ) {

        Jwt jwt = getCurrentJwt();

        if (jwt == null) {
            log.warn(
                    "Skipping outbox notification creation because authenticated JWT is unavailable for booking {}",
                    booking.getBookingReference()
            );
            return;
        }

        BookingConfirmedNotificationRequest request =
                BookingConfirmedNotificationRequest.builder()
                        .userId(booking.getUserId())
                        .email(resolveEmail(jwt))
                        .name(resolveDisplayName(jwt))
                        .bookingId(booking.getBookingReference())
                        .eventTitle(booking.getEventTitleSnapshot())
                        .bookingDate(
                                String.valueOf(
                                        booking.getCreatedAt() != null
                                                ? booking.getCreatedAt()
                                                : LocalDateTime.now()
                                )
                        )
                        .build();

        if (request.getEmail() == null
                || request.getEmail().isBlank()) {

            log.warn(
                    "Skipping outbox notification creation because email is unavailable for booking {}",
                    booking.getBookingReference()
            );

            return;
        }

        Map<String, Object> payload = new HashMap<>();

        payload.put("userId", request.getUserId());
        payload.put("email", request.getEmail());
        payload.put("name", request.getName());
        payload.put("bookingId", request.getBookingId());
        payload.put("eventTitle", request.getEventTitle());
        payload.put("bookingDate", request.getBookingDate());
        payload.put("correlationId", correlationId);

        String payloadJson;

        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to serialize booking notification payload",
                    ex
            );
        }

        OutboxEvent outboxEvent =
                OutboxEvent.builder()
                        .eventType("BOOKING_CONFIRMED")
                        .aggregateType("BOOKING")
                        .aggregateId(booking.getBookingReference())
                        .correlationId(correlationId)
                        .payload(payloadJson)
                        .status("PENDING")
                        .retryCount(0)
                        .build();

        outboxEventRepository.save(outboxEvent);
    }
}
