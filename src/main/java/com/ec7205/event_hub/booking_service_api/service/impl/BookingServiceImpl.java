package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketSelectionRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.ApiMessageResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventTicketTypeResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.entity.BookingItem;
import com.ec7205.event_hub.booking_service_api.entity.Payment;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import com.ec7205.event_hub.booking_service_api.enums.PaymentStatus;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.exception.ResourceNotFoundException;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.repository.PaymentRepository;
import com.ec7205.event_hub.booking_service_api.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String PUBLISHED_EVENT_STATUS = "PUBLISHED";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final EventServiceClient eventServiceClient;
    private final BookingMapper bookingMapper;

    @Override
    @Transactional(noRollbackFor = PaymentFailedException.class)
    public CreateBookingResponse createBooking(Long userId, CreateBookingRequest request) {
        validateCreateRequest(userId, request);

        EventBookingInfoResponse eventInfo = eventServiceClient.getEventBookingInfo(request.getEventId());
        validateEventForBooking(eventInfo);

        Map<Long, EventTicketTypeResponse> ticketTypeMap = validateAndMapTicketTypes(request, eventInfo);
        Booking booking = initializeBooking(userId, eventInfo);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (TicketSelectionRequest selection : request.getTicketSelections()) {
            EventTicketTypeResponse ticketType = ticketTypeMap.get(selection.getTicketTypeId());
            BigDecimal subtotal = ticketType.getPrice().multiply(BigDecimal.valueOf(selection.getQuantity()));

            BookingItem item = BookingItem.builder()
                    .ticketTypeId(ticketType.getTicketTypeId())
                    .ticketTypeNameSnapshot(ticketType.getTicketTypeName())
                    .unitPrice(ticketType.getPrice())
                    .quantity(selection.getQuantity())
                    .subtotal(subtotal)
                    .build();
            booking.addItem(item);
            totalAmount = totalAmount.add(subtotal);
        }

        booking.setTotalAmount(totalAmount);

        // TODO Integrate distributed inventory locking and availability checks with Event Service.
        Booking savedBooking = bookingRepository.save(booking);

        Payment payment = simulatePayment(savedBooking, request.getPaymentMethod(), totalAmount);
        paymentRepository.save(payment);

        if (payment.getStatus() == PaymentStatus.FAILED) {
            savedBooking.setStatus(BookingStatus.FAILED);
            savedBooking.setPayment(payment);
            bookingRepository.save(savedBooking);
            throw new PaymentFailedException("Payment failed for booking reference: " + savedBooking.getBookingReference());
        }

        savedBooking.setStatus(BookingStatus.CONFIRMED);
        savedBooking.setPayment(payment);
        Booking confirmedBooking = bookingRepository.save(savedBooking);

        // TODO Publish BOOKING_CONFIRMED event for downstream notification and analytics flows.
        return bookingMapper.toCreateBookingResponse(confirmedBooking);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BookingSummaryResponse> getMyBookings(Long userId, Pageable pageable) {
        if (userId == null) {
            throw new BadRequestException("X-User-Id header is required");
        }

        return bookingRepository.findByUserId(userId, pageable)
                .map(bookingMapper::toBookingSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetails(Long bookingId, Long userId, String userRole) {
        Booking booking = getBookingWithRelations(bookingId);
        assertOwnerOrAdmin(booking, userId, userRole);
        return bookingMapper.toBookingDetailResponse(booking);
    }

    @Override
    @Transactional
    public ApiMessageResponse cancelBooking(Long bookingId, Long userId, String userRole) {
        Booking booking = getBookingWithRelations(bookingId);
        assertOwnerOrAdmin(booking, userId, userRole);

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("Only confirmed bookings can be cancelled");
        }

        if (!booking.getEventStartDateTimeSnapshot().isAfter(LocalDateTime.now())) {
            throw new ConflictException("Booking cannot be cancelled because the event has already started");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        if (booking.getPayment() != null && booking.getPayment().getStatus() == PaymentStatus.SUCCESS) {
            booking.getPayment().setStatus(PaymentStatus.REFUNDED);
        }

        bookingRepository.save(booking);

        // TODO Publish BOOKING_CANCELLED event for notification and seat release workflows.
        return ApiMessageResponse.builder()
                .message("Booking cancelled successfully")
                .build();
    }

    private void validateCreateRequest(Long userId, CreateBookingRequest request) {
        if (userId == null) {
            throw new BadRequestException("X-User-Id header is required");
        }
        if (request.getTicketSelections() == null || request.getTicketSelections().isEmpty()) {
            throw new BadRequestException("At least one ticket selection is required");
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
        if (eventInfo.getTicketTypes() == null || eventInfo.getTicketTypes().isEmpty()) {
            throw new ConflictException("No ticket types are available for this event");
        }

        Map<Long, EventTicketTypeResponse> ticketTypeMap = new HashMap<>();
        for (EventTicketTypeResponse ticketType : eventInfo.getTicketTypes()) {
            ticketTypeMap.put(ticketType.getTicketTypeId(), ticketType);
        }

        Set<Long> seenTicketTypeIds = new HashSet<>();
        for (TicketSelectionRequest selection : request.getTicketSelections()) {
            if (selection.getQuantity() == null || selection.getQuantity() <= 0) {
                throw new BadRequestException("Each ticket quantity must be greater than zero");
            }
            if (!seenTicketTypeIds.add(selection.getTicketTypeId())) {
                throw new BadRequestException("Duplicate ticket type selections are not allowed");
            }
            if (!ticketTypeMap.containsKey(selection.getTicketTypeId())) {
                throw new BadRequestException("Invalid ticket type requested: " + selection.getTicketTypeId());
            }
        }

        return ticketTypeMap;
    }

    private Booking initializeBooking(Long userId, EventBookingInfoResponse eventInfo) {
        return Booking.builder()
                .bookingReference(generateBookingReference())
                .userId(userId)
                .eventId(eventInfo.getEventId())
                .eventTitleSnapshot(eventInfo.getTitle())
                .eventStartDateTimeSnapshot(eventInfo.getStartDateTime())
                .status(BookingStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.ZERO)
                .build();
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

    private void assertOwnerOrAdmin(Booking booking, Long userId, String userRole) {
        if (isAdmin(userRole)) {
            return;
        }

        if (userId == null) {
            throw new BadRequestException("X-User-Id header is required");
        }

        if (!booking.getUserId().equals(userId)) {
            throw new UnauthorizedActionException("You are not allowed to access this booking");
        }
    }

    private boolean isAdmin(String userRole) {
        return ADMIN_ROLE.equalsIgnoreCase(userRole);
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
}
