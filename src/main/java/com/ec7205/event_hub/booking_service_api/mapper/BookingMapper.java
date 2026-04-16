package com.ec7205.event_hub.booking_service_api.mapper;

import com.ec7205.event_hub.booking_service_api.dto.response.AdminBookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingDetailResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingItemResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.BookingSummaryResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.CreateBookingResponse;
import com.ec7205.event_hub.booking_service_api.dto.response.PaymentResponse;
import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.entity.BookingItem;
import com.ec7205.event_hub.booking_service_api.entity.Payment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class BookingMapper {

    public CreateBookingResponse toCreateBookingResponse(Booking booking) {
        return CreateBookingResponse.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .eventId(booking.getEventId())
                .eventTitle(booking.getEventTitleSnapshot())
                .eventBannerResourceUrl(booking.getEventBannerResourceUrlSnapshot())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .bookingDate(booking.getCreatedAt())
                .items(toItemResponses(booking.getItems()))
                .payment(toPaymentResponse(booking.getPayment()))
                .build();
    }

    public BookingSummaryResponse toBookingSummaryResponse(Booking booking) {
        return BookingSummaryResponse.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .eventId(booking.getEventId())
                .eventTitle(booking.getEventTitleSnapshot())
                .eventStartDateTime(booking.getEventStartDateTimeSnapshot())
                .eventBannerResourceUrl(booking.getEventBannerResourceUrlSnapshot())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .bookingDate(booking.getCreatedAt())
                .items(toItemResponses(booking.getItems()))
                .build();
    }

    public AdminBookingSummaryResponse toAdminBookingSummaryResponse(Booking booking) {
        return AdminBookingSummaryResponse.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .userId(booking.getUserId())
                .eventId(booking.getEventId())
                .eventTitle(booking.getEventTitleSnapshot())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .bookingDate(booking.getCreatedAt())
                .build();
    }

    public BookingDetailResponse toBookingDetailResponse(Booking booking) {
        return BookingDetailResponse.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .userId(booking.getUserId())
                .eventId(booking.getEventId())
                .eventTitle(booking.getEventTitleSnapshot())
                .eventBannerResourceUrl(booking.getEventBannerResourceUrlSnapshot())
                .eventStartDateTime(booking.getEventStartDateTimeSnapshot())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .bookingDate(booking.getCreatedAt())
                .items(toItemResponses(booking.getItems()))
                .payment(toPaymentResponse(booking.getPayment()))
                .build();
    }

    public List<BookingItemResponse> toItemResponses(List<BookingItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        return items.stream()
                .map(this::toItemResponse)
                .toList();
    }

    public BookingItemResponse toItemResponse(BookingItem item) {
        return BookingItemResponse.builder()
                .ticketTypeId(item.getTicketTypeId())
                .ticketTypeName(item.getTicketTypeNameSnapshot())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .subtotal(item.getSubtotal())
                .build();
    }

    public PaymentResponse toPaymentResponse(Payment payment) {
        if (payment == null) {
            return null;
        }

        return PaymentResponse.builder()
                .method(payment.getMethod())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .transactionReference(payment.getTransactionReference())
                .paidAt(payment.getPaidAt())
                .build();
    }
}
