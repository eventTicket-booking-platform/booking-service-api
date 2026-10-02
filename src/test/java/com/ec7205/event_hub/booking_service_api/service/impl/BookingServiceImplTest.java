package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.client.EventServiceClient;
import com.ec7205.event_hub.booking_service_api.dto.request.CreateBookingRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.ReserveTicketsRequest;
import com.ec7205.event_hub.booking_service_api.dto.request.TicketSelectionRequest;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.EventTicketTypeResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.entity.Payment;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.PaymentMethod;
import com.ec7205.event_hub.booking_service_api.exception.BadRequestException;
import com.ec7205.event_hub.booking_service_api.exception.ConflictException;
import com.ec7205.event_hub.booking_service_api.exception.PaymentFailedException;
import com.ec7205.event_hub.booking_service_api.mapper.BookingMapper;
import com.ec7205.event_hub.booking_service_api.messaging.BookingNotificationEventPublisher;
import com.ec7205.event_hub.booking_service_api.repository.BookingRepository;
import com.ec7205.event_hub.booking_service_api.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @InjectMocks
    private BookingServiceImpl bookingService;

    @Test
    void shouldReserveTicketsAndCreateConfirmedBooking() {

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

        EventTicketTypeResponse ticket =
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
                        .ticketTypes(List.of(ticket))
                        .build();

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
    void shouldKeepBookingPendingWhenPaymentFails() {

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

        Booking existingBooking = Booking.builder()
                .id(1L)
                .bookingReference("BK-EXISTING")
                .idempotencyKey("key-123")
                .userId("user-1")
                .eventId(100L)
                .eventTitleSnapshot("Test Event")
                .eventStartDateTimeSnapshot(LocalDateTime.now().plusDays(1))
                .status(BookingStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(5000))
                .build();

        CreateBookingResponse existingResponse =
                CreateBookingResponse.builder()
                        .bookingId(1L)
                        .bookingReference("BK-EXISTING")
                        .status(BookingStatus.CONFIRMED)
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

        when(
                bookingRepository.findByUserIdAndIdempotencyKey(
                        "user-1",
                        "key-123"
                )
        ).thenReturn(Optional.of(existingBooking));

        when(bookingMapper.toCreateBookingResponse(existingBooking))
                .thenReturn(existingResponse);

        CreateBookingResponse response =
                bookingService.createBooking(
                        "user-1",
                        "key-123",
                        request
                );

        assertEquals("BK-EXISTING", response.getBookingReference());

        verify(eventServiceClient, never())
                .getEventBookingInfo(anyLong());

        verify(eventServiceClient, never())
                .reserveTickets(anyLong(), any());

        verify(bookingRepository, never())
                .save(any());

        verify(paymentRepository, never())
                .save(any());
    }

    @Test
    void shouldCreateNewBookingWhenIdempotencyKeyDoesNotExist() {

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

        when(
                bookingRepository.findByUserIdAndIdempotencyKey(
                        "user-1",
                        "new-key"
                )
        ).thenReturn(Optional.empty());

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

        verify(bookingRepository, atLeastOnce())
                .save(any(Booking.class));
    }

    @Test
    void shouldStoreIdempotencyKeyInNewBooking() {

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

        when(
                bookingRepository.findByUserIdAndIdempotencyKey(
                        "user-1",
                        "key-abc"
                )
        ).thenReturn(Optional.empty());

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(CreateBookingResponse.builder().build());

        bookingService.createBooking(
                "user-1",
                "key-abc",
                request
        );

        ArgumentCaptor<Booking> bookingCaptor =
                ArgumentCaptor.forClass(Booking.class);

        verify(bookingRepository, atLeastOnce())
                .save(bookingCaptor.capture());

        assertEquals(
                "key-abc",
                bookingCaptor.getAllValues().get(0).getIdempotencyKey()
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

        when(
                bookingRepository.findByUserIdAndIdempotencyKey(
                        "user-2",
                        "same-key"
                )
        ).thenReturn(Optional.empty());

        when(eventServiceClient.getEventBookingInfo(100L))
                .thenReturn(eventInfo);

        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingMapper.toCreateBookingResponse(any(Booking.class)))
                .thenReturn(CreateBookingResponse.builder().build());

        bookingService.createBooking(
                "user-2",
                "same-key",
                request
        );

        verify(eventServiceClient)
                .reserveTickets(
                        eq(100L),
                        any(ReserveTicketsRequest.class)
                );

        verify(paymentRepository)
                .save(any(Payment.class));
    }

    
}