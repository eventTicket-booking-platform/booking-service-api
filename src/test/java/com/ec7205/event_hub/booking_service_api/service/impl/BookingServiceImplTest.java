package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.ReserveTicketsRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketSelectionRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventTicketTypeResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.entity.IdempotencyRecord;
import com.ec7205.event_hub.booking_service_api.entity.Payment;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.IdempotencyStatus;
import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.repository.PaymentRepository;
import com.ec7205.event_hub.booking_service_api.service.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private EventServiceClient eventServiceClient;

    @Mock
    private BookingNotificationEventPublisher bookingNotificationEventPublisher;

    @Mock
    private BookingMapper bookingMapper;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private BookingServiceImpl bookingService;

    @Test
    void shouldReserveTicketsAndCreateConfirmedBooking() {
        stubNewIdempotencyClaim();
        // ARRANGE
        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(
                        CreateBookingResponse.builder()
                                .status(BookingStatus.CONFIRMED)
                                .build()
                );

        // ACT
        CreateBookingResponse response =
                bookingService.createBooking("user-1", "new-key",request);

        // ASSERT

        ArgumentCaptor<ReserveTicketsRequest> reserveCaptor =
                ArgumentCaptor.forClass(ReserveTicketsRequest.class);

        verify(eventServiceClient).reserveTickets(
                eq(100L),
                reserveCaptor.capture()
        );

        ReserveTicketsRequest reserveRequest =
                reserveCaptor.getValue();

        assertEquals(1, reserveRequest.getTickets().size());
        assertEquals(1L, reserveRequest.getTickets().get(0).getTicketTypeId());
        assertEquals(2, reserveRequest.getTickets().get(0).getQuantity());

        verify(bookingRepository, atLeastOnce())
                .save(any(Booking.class));

        verify(paymentRepository)
                .save(any(Payment.class));

        assertEquals(
                BookingStatus.CONFIRMED,
                response.getStatus()
        );
    }

    @Test
    void shouldNotCreateBookingWhenTicketReservationFails() {
        stubNewIdempotencyClaim();
        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        doThrow(new ConflictException("Not enough tickets"))
                .when(eventServiceClient)
                .reserveTickets(eq(100L), any(ReserveTicketsRequest.class));

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking("user-1", "new-key",request)
        );

        verify(bookingRepository, never())
                .save(any(Booking.class));

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void shouldRejectBookingWhenEventIsNotPublished() {
        stubNewIdempotencyClaim();
        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Draft Event")
                        .status("DRAFT")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of())
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking("user-1", "new-key",request)
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());

        verify(bookingRepository, never())
                .save(any());
    }

    @Test
    void shouldRejectBookingWhenEventHasAlreadyStarted() {
        stubNewIdempotencyClaim();
        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Past Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().minusHours(1))
                        .ticketTypes(List.of())
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking("user-1", "new-key",request)
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());

        verify(bookingRepository, never())
                .save(any());
    }

    @Test
    void shouldRejectInvalidTicketType() {
        stubNewIdempotencyClaim();
        EventTicketTypeResponse validTicket =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(validTicket))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(999L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        assertThrows(
                BadRequestException.class,
                () -> bookingService.createBooking("user-1", "new-key",request)
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());
    }

    @Test
    void shouldRejectDuplicateTicketSelections() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build(),

                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        assertThrows(
                BadRequestException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "new-key",
                        request
                )
        );

        verifyNoInteractions(eventServiceClient);
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void shouldKeepBookingPendingWhenPaymentFails() {
        stubNewIdempotencyClaim();
        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.SIMULATED_FAIL)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(
                PaymentFailedException.class,
                () -> bookingService.createBooking("user-1","new-key", request)
        );

        verify(eventServiceClient)
                .reserveTickets(eq(100L), any(ReserveTicketsRequest.class));

        verify(paymentRepository)
                .save(any(Payment.class));

        ArgumentCaptor<Booking> bookingCaptor =
                ArgumentCaptor.forClass(Booking.class);

        verify(bookingRepository, atLeastOnce())
                .save(bookingCaptor.capture());

        Booking savedBooking =
                bookingCaptor.getValue();

        assertEquals(
                BookingStatus.PENDING,
                savedBooking.getStatus()
        );
    }

    @Test
    void shouldReleaseTicketsWhenPaymentFails() {
        stubNewIdempotencyClaim();
        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.SIMULATED_FAIL)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(
                PaymentFailedException.class,
                () -> bookingService.createBooking("user-1", "new-key",request)
        );

        verify(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        ArgumentCaptor<ReserveTicketsRequest> releaseCaptor =
                ArgumentCaptor.forClass(ReserveTicketsRequest.class);

        verify(eventServiceClient)
                .releaseTickets(
                        eq(100L),
                        releaseCaptor.capture()
                );

        ReserveTicketsRequest releaseRequest =
                releaseCaptor.getValue();

        assertEquals(1, releaseRequest.getTickets().size());
        assertEquals(
                1L,
                releaseRequest.getTickets().get(0).getTicketTypeId()
        );
        assertEquals(
                2,
                releaseRequest.getTickets().get(0).getQuantity()
        );
    }

    @Test
    void shouldNotReleaseTicketsWhenPaymentSucceeds() {
        stubNewIdempotencyClaim();
        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(
                        CreateBookingResponse.builder()
                                .status(BookingStatus.CONFIRMED)
                                .build()
                );

        bookingService.createBooking("user-1", "new-key",request);

        verify(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        verify(eventServiceClient, never())
                .releaseTickets(
                        anyLong(),
                        any(ReserveTicketsRequest.class)
                );
    }

    @Test
    void shouldReturnExistingBookingWhenIdempotencyKeyAlreadyExists() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        String matchingFingerprint =
                generateFingerprintForTest(
                        100L,
                        PaymentMethod.CARD,
                        1L,
                        2
                );

        IdempotencyRecord existingRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint(matchingFingerprint)
                        .status(IdempotencyStatus.COMPLETED)
                        .bookingId(1L)
                        .build();

        Booking existingBooking =
                Booking.builder()
                        .id(1L)
                        .bookingReference("BK-EXISTING")
                        .userId("user-1")
                        .eventId(100L)
                        .eventTitleSnapshot("Test Event")
                        .eventStartDateTimeSnapshot(
                                LocalDateTime.now().plusDays(1)
                        )
                        .status(BookingStatus.CONFIRMED)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .build();

        CreateBookingResponse existingResponse =
                CreateBookingResponse.builder()
                        .bookingId(1L)
                        .bookingReference("BK-EXISTING")
                        .status(BookingStatus.CONFIRMED)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "key-123"
                )
        ).thenReturn(Optional.of(existingRecord));

        when(bookingRepository.findById(1L))
                .thenReturn(Optional.of(existingBooking));

        when(
                bookingMapper.toCreateBookingResponse(
                        existingBooking
                )
        ).thenReturn(existingResponse);

        CreateBookingResponse response =
                bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                );

        assertEquals(
                "BK-EXISTING",
                response.getBookingReference()
        );

        verify(idempotencyService, never())
                .claim(
                        anyString(),
                        anyString(),
                        anyString()
                );

        verify(eventServiceClient, never())
                .getEventBookingInfo(anyLong());

        verify(eventServiceClient, never())
                .reserveTickets(
                        anyLong(),
                        any()
                );

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldCreateNewBookingWhenIdempotencyKeyDoesNotExist() {

        IdempotencyRecord claimedRecord =
                stubNewIdempotencyClaim();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(
                                LocalDateTime.now().plusDays(1)
                        )
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(2)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {

                    Booking booking =
                            invocation.getArgument(0);

                    if (booking.getId() == null) {
                        booking.setId(99L);
                    }

                    return booking;
                });

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(
                        invocation -> invocation.getArgument(0)
                );

        when(
                bookingMapper.toCreateBookingResponse(
                        any(Booking.class)
                )
        ).thenReturn(
                CreateBookingResponse.builder()
                        .bookingId(99L)
                        .status(BookingStatus.CONFIRMED)
                        .build()
        );

        bookingService.createBooking(
                "user-1",
                "new-key",
                request
        );

        verify(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        verify(paymentRepository)
                .save(any(Payment.class));

        verify(idempotencyService)
                .markCompleted(
                        claimedRecord.getId(),
                        99L
                );
    }

    @Test
    void shouldStoreIdempotencyKeyInNewBooking() {

        stubNewIdempotencyClaim();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(
                                LocalDateTime.now().plusDays(1)
                        )
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {

                    Booking booking =
                            invocation.getArgument(0);

                    if (booking.getId() == null) {
                        booking.setId(99L);
                    }

                    return booking;
                });

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(
                        invocation -> invocation.getArgument(0)
                );

        when(
                bookingMapper.toCreateBookingResponse(
                        any(Booking.class)
                )
        ).thenReturn(
                CreateBookingResponse.builder()
                        .bookingId(99L)
                        .build()
        );

        bookingService.createBooking(
                "user-1",
                "key-abc",
                request
        );

        ArgumentCaptor<Booking> bookingCaptor =
                ArgumentCaptor.forClass(Booking.class);

        verify(bookingRepository, atLeastOnce())
                .save(bookingCaptor.capture());

        Booking firstSavedBooking =
                bookingCaptor.getAllValues().get(0);

        assertEquals(
                "key-abc",
                firstSavedBooking.getIdempotencyKey()
        );

        assertNotNull(
                firstSavedBooking.getRequestFingerprint()
        );

        assertEquals(
                64,
                firstSavedBooking
                        .getRequestFingerprint()
                        .length()
        );
    }

    @Test
    void shouldRejectBookingWhenIdempotencyKeyIsBlank() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        assertThrows(
                BadRequestException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "   ",
                        request
                )
        );

        verifyNoInteractions(eventServiceClient);

        verify(bookingRepository, never())
                .save(any());

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldRejectBookingWhenIdempotencyKeyIsNull() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        assertThrows(
                BadRequestException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        null,
                        request
                )
        );

        verifyNoInteractions(eventServiceClient);

        verify(bookingRepository, never())
                .save(any());

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldNotReuseAnotherUsersBookingForSameIdempotencyKey() {

        IdempotencyRecord claimedRecord =
                stubNewIdempotencyClaim();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);

                    if (booking.getId() == null) {
                        booking.setId(99L);
                    }

                    return booking;
                });

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(
                        CreateBookingResponse.builder()
                                .bookingId(99L)
                                .status(BookingStatus.CONFIRMED)
                                .build()
                );

        bookingService.createBooking(
                "user-2",
                "same-key",
                request
        );

        verify(idempotencyService)
                .find(
                        "user-2",
                        "same-key"
                );

        verify(idempotencyService)
                .claim(
                        eq("user-2"),
                        eq("same-key"),
                        anyString()
                );

        verify(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        verify(paymentRepository)
                .save(any(Payment.class));

        verify(idempotencyService)
                .markCompleted(
                        claimedRecord.getId(),
                        99L
                );
    }
    
    @Test
    void shouldReturnExistingBookingWhenSameKeyAndSameRequest() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        String matchingFingerprint =
                generateFingerprintForTest(
                        100L,
                        PaymentMethod.CARD,
                        1L,
                        2
                );

        IdempotencyRecord existingRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint(matchingFingerprint)
                        .status(IdempotencyStatus.COMPLETED)
                        .bookingId(1L)
                        .build();

        Booking existingBooking =
                Booking.builder()
                        .id(1L)
                        .bookingReference("BK-EXISTING")
                        .userId("user-1")
                        .eventId(100L)
                        .status(BookingStatus.CONFIRMED)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .build();

        CreateBookingResponse existingResponse =
                CreateBookingResponse.builder()
                        .bookingId(1L)
                        .bookingReference("BK-EXISTING")
                        .status(BookingStatus.CONFIRMED)
                        .build();

        when(idempotencyService.find(
                "user-1",
                "key-123"
        )).thenReturn(Optional.of(existingRecord));

        when(bookingRepository.findById(1L))
                .thenReturn(Optional.of(existingBooking));

        when(bookingMapper.toCreateBookingResponse(existingBooking))
                .thenReturn(existingResponse);

        CreateBookingResponse response =
                bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                );

        assertEquals(
                "BK-EXISTING",
                response.getBookingReference()
        );

        verify(idempotencyService, never())
                .claim(anyString(), anyString(), anyString());

        verify(eventServiceClient, never())
                .getEventBookingInfo(anyLong());
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentRequest() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(5)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        IdempotencyRecord existingRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint("different-fingerprint")
                        .status(IdempotencyStatus.COMPLETED)
                        .bookingId(1L)
                        .build();

        when(idempotencyService.find(
                "user-1",
                "key-123"
        )).thenReturn(Optional.of(existingRecord));

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                )
        );

        verify(idempotencyService, never())
                .claim(anyString(), anyString(), anyString());

        verify(eventServiceClient, never())
                .getEventBookingInfo(anyLong());

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());
    }
    private String generateFingerprintForTest(
            Long eventId,
            PaymentMethod paymentMethod,
            Long ticketTypeId,
            Integer quantity
    ) {
        String raw =
                eventId
                        + "|" + paymentMethod
                        + "|" + ticketTypeId
                        + ":" + quantity;

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            raw.getBytes(StandardCharsets.UTF_8)
                    );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    e
            );
        }
    }
//-------------------------------------------------------------------------------------

    @Test
    void shouldRejectRequestWhenIdempotencyRecordIsProcessing() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .id(1L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint(
                                generateFingerprintForTest(
                                        100L,
                                        PaymentMethod.CARD,
                                        1L,
                                        2
                                )
                        )
                        .status(IdempotencyStatus.PROCESSING)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "key-123"
                )
        ).thenReturn(Optional.of(record));

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                )
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldReturnExistingBookingWhenIdempotencyRecordIsCompleted() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        String fingerprint =
                generateFingerprintForTest(
                        100L,
                        PaymentMethod.CARD,
                        1L,
                        2
                );

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .id(1L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint(fingerprint)
                        .status(IdempotencyStatus.COMPLETED)
                        .bookingId(99L)
                        .build();

        Booking existingBooking =
                Booking.builder()
                        .id(99L)
                        .bookingReference("BK-EXISTING")
                        .userId("user-1")
                        .eventId(100L)
                        .eventTitleSnapshot("Test Event")
                        .eventStartDateTimeSnapshot(LocalDateTime.now().plusDays(1))
                        .status(BookingStatus.CONFIRMED)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .build();

        CreateBookingResponse existingResponse =
                CreateBookingResponse.builder()
                        .bookingId(99L)
                        .bookingReference("BK-EXISTING")
                        .status(BookingStatus.CONFIRMED)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "key-123"
                )
        ).thenReturn(Optional.of(record));

        when(bookingRepository.findById(99L))
                .thenReturn(Optional.of(existingBooking));

        when(bookingMapper.toCreateBookingResponse(existingBooking))
                .thenReturn(existingResponse);

        CreateBookingResponse response =
                bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                );

        assertEquals(
                "BK-EXISTING",
                response.getBookingReference()
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldRejectRequestWhenPreviousIdempotentRequestFailed() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .id(1L)
                        .userId("user-1")
                        .idempotencyKey("key-123")
                        .requestFingerprint(
                                generateFingerprintForTest(
                                        100L,
                                        PaymentMethod.CARD,
                                        1L,
                                        2
                                )
                        )
                        .status(IdempotencyStatus.FAILED)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "key-123"
                )
        ).thenReturn(Optional.of(record));

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                )
        );

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());
    }

    @Test
    void shouldMarkIdempotencyRecordCompletedWhenBookingSucceeds() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        IdempotencyRecord claimedRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("new-key")
                        .status(IdempotencyStatus.PROCESSING)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "new-key"
                )
        ).thenReturn(Optional.empty());

        when(
                idempotencyService.claim(
                        eq("user-1"),
                        eq("new-key"),
                        anyString()
                )
        ).thenReturn(claimedRecord);

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);

                    if (booking.getId() == null) {
                        booking.setId(99L);
                    }

                    return booking;
                });

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(
                        CreateBookingResponse.builder()
                                .bookingId(99L)
                                .status(BookingStatus.CONFIRMED)
                                .build()
                );

        bookingService.createBooking(
                "user-1",
                "new-key",
                request
        );

        verify(idempotencyService)
                .markCompleted(
                        10L,
                        99L
                );
    }

    @Test
    void shouldMarkIdempotencyRecordFailedWhenBookingProcessingFails() {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(List.of(
                                TicketSelectionRequest.builder()
                                        .ticketTypeId(1L)
                                        .quantity(2)
                                        .build()
                        ))
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(LocalDateTime.now().plusDays(1))
                        .ticketTypes(List.of(ticketType))
                        .build();

        IdempotencyRecord claimedRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("new-key")
                        .status(IdempotencyStatus.PROCESSING)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "new-key"
                )
        ).thenReturn(Optional.empty());

        when(
                idempotencyService.claim(
                        eq("user-1"),
                        eq("new-key"),
                        anyString()
                )
        ).thenReturn(claimedRecord);

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        doThrow(new ConflictException("Not enough tickets"))
                .when(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        assertThrows(
                ConflictException.class,
                () -> bookingService.createBooking(
                        "user-1",
                        "new-key",
                        request
                )
        );

        verify(idempotencyService)
                .markFailed(10L);

        verify(paymentRepository, never())
                .save(any());
    }

    private IdempotencyRecord stubNewIdempotencyClaim() {

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("test-key")
                        .status(IdempotencyStatus.PROCESSING)
                        .build();

        when(idempotencyService.find(
                anyString(),
                anyString()
        )).thenReturn(Optional.empty());

        when(idempotencyService.claim(
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(record);

        return record;
    }

    //-------------------------------------------------------------------------------------
    @Test
    void shouldAllowOnlyOneConcurrentBookingForSameIdempotencyKey()
            throws Exception {

        CreateBookingRequest request =
                CreateBookingRequest.builder()
                        .eventId(100L)
                        .ticketSelections(
                                List.of(
                                        TicketSelectionRequest.builder()
                                                .ticketTypeId(1L)
                                                .quantity(1)
                                                .build()
                                )
                        )
                        .paymentMethod(PaymentMethod.CARD)
                        .build();

        EventTicketTypeResponse ticketType =
                EventTicketTypeResponse.builder()
                        .ticketTypeId(1L)
                        .ticketTypeName("Standard")
                        .price(BigDecimal.valueOf(2500))
                        .build();

        EventBookingInfoResponse eventInfo =
                EventBookingInfoResponse.builder()
                        .eventId(100L)
                        .title("Test Event")
                        .status("PUBLISHED")
                        .startDateTime(
                                LocalDateTime.now().plusDays(1)
                        )
                        .ticketTypes(List.of(ticketType))
                        .build();

        IdempotencyRecord claimedRecord =
                IdempotencyRecord.builder()
                        .id(10L)
                        .userId("user-1")
                        .idempotencyKey("same-key")
                        .status(IdempotencyStatus.PROCESSING)
                        .build();

        when(
                idempotencyService.find(
                        "user-1",
                        "same-key"
                )
        ).thenReturn(Optional.empty());

        AtomicInteger claimCount =
                new AtomicInteger(0);

        when(
                idempotencyService.claim(
                        eq("user-1"),
                        eq("same-key"),
                        anyString()
                )
        ).thenAnswer(invocation -> {

            if (claimCount.incrementAndGet() == 1) {
                return claimedRecord;
            }

            throw new DataIntegrityViolationException(
                    "Duplicate idempotency key"
            );
        });

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {

                    Booking booking =
                            invocation.getArgument(0);

                    if (booking.getId() == null) {
                        booking.setId(99L);
                    }

                    return booking;
                });

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(
                        invocation -> invocation.getArgument(0)
                );

        when(
                bookingMapper.toCreateBookingResponse(
                        any(Booking.class)
                )
        ).thenReturn(
                CreateBookingResponse.builder()
                        .bookingId(99L)
                        .status(BookingStatus.CONFIRMED)
                        .build()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        Callable<Boolean> task = () -> {

            ready.countDown();

            start.await();

            try {
                bookingService.createBooking(
                        "user-1",
                        "same-key",
                        request
                );

                return true;

            } catch (ConflictException ex) {
                return false;
            }
        };

        Future<Boolean> first =
                executor.submit(task);

        Future<Boolean> second =
                executor.submit(task);

        ready.await();

        start.countDown();

        boolean firstResult =
                first.get();

        boolean secondResult =
                second.get();

        executor.shutdown();

        long successCount =
                List.of(
                                firstResult,
                                secondResult
                        )
                        .stream()
                        .filter(Boolean::booleanValue)
                        .count();

        assertEquals(
                1,
                successCount
        );

        verify(idempotencyService, times(2))
                .claim(
                        eq("user-1"),
                        eq("same-key"),
                        anyString()
                );

        verify(eventServiceClient, times(1))
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        verify(paymentRepository, times(1))
                .save(any(Payment.class));

        verify(idempotencyService, times(1))
                .markCompleted(
                        10L,
                        99L
                );
    }
}