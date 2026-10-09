package com.ec7205.event_hub.booking_service_api.service.impl;

import com.ec7205.event_hub.booking_service_api.config.*;
import com.ec7205.event_hub.booking_service_api.controller.*;
import com.ec7205.event_hub.booking_service_api.exception.UnauthorizedActionException;
import com.ec7205.event_hub.booking_service_api.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({BookingController.class, AdminBookingController.class, InternalBookingController.class})
@Import({SecurityConfig.class, JwtAuthConverter.class})
@ActiveProfiles("test")
class BookingAuthorizationHttpTest {
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean BookingService bookings;
    @MockBean AdminBookingService admin;
    @MockBean InternalBookingService counts;
    @MockBean com.ec7205.event_hub.booking_service_api.client.EventServiceClient events;

    @Test void directInternalCountCannotBypassHostOwnership() throws Exception {
        token("host-1", "HOST");
        when(events.getEventBookingInfo(100L)).thenReturn(
                com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse.builder().createdBy("host-1").build());
        when(events.getEventBookingInfo(200L)).thenReturn(
                com.ec7205.event_hub.booking_service_api.dto.response.EventBookingInfoResponse.builder().createdBy("host-2").build());
        mvc.perform(get("/booking-service/api/v1/bookings/internal/event/100/count").header("Authorization", "Bearer host-1"))
                .andExpect(status().isOk());
        mvc.perform(get("/booking-service/api/v1/bookings/internal/event/200/count").header("Authorization", "Bearer host-1"))
                .andExpect(status().isForbidden());
        token("customer", "CUSTOMER");
        mvc.perform(get("/booking-service/api/v1/bookings/internal/event/100/count").header("Authorization", "Bearer customer"))
                .andExpect(status().isForbidden());
        verify(counts, times(1)).getConfirmedBookingCountByEvent(100L);
        verify(counts, never()).getConfirmedBookingCountByEvent(200L);
    }

    @Test void unauthenticatedRequestsUseExisting401Handling() throws Exception {
        mvc.perform(get("/booking-service/api/v1/admin/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/booking-service/api/v1/bookings/1")).andExpect(status().isUnauthorized());
    }
    @Test void customerCannotUseAdminHostEndpoints() throws Exception {
        token("customer-1", "CUSTOMER");
        mvc.perform(get("/booking-service/api/v1/admin/all").header("Authorization", "Bearer customer-1"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/booking-service/api/v1/admin/stats").header("Authorization", "Bearer customer-1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(admin);
        mvc.perform(get("/booking-service/api/v1/bookings/1").header("Authorization", "Bearer customer-1"))
                .andExpect(status().isOk());
        verify(bookings).getBookingDetails(1L, "customer-1", "CUSTOMER");
    }
    @Test void hostDetailOwnershipFailureReturns403() throws Exception {
        token("host-1", "HOST");
        when(bookings.getBookingDetails(1L, "host-1", "HOST"))
                .thenThrow(new UnauthorizedActionException("You do not own this booking's event"));
        mvc.perform(get("/booking-service/api/v1/bookings/1").header("Authorization", "Bearer host-1"))
                .andExpect(status().isForbidden());
    }
    @Test void adminTakesPrecedenceForMultiRoleJwtAndCallerTokenIsForwarded() throws Exception {
        token("admin", "HOST", "ADMIN");
        mvc.perform(get("/booking-service/api/v1/admin/stats").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
        verify(admin).getBookingStats("ADMIN", "Bearer admin", null);
        token("host-1", "host");
        mvc.perform(get("/booking-service/api/v1/admin/stats?eventId=100").header("Authorization", "Bearer host-1"))
                .andExpect(status().isOk());
        verify(admin).getBookingStats("HOST", "Bearer host-1", 100L);
    }
    private void token(String subject, String... roles) {
        when(decoder.decode(subject)).thenReturn(Jwt.withTokenValue(subject).header("alg", "none")
                .subject(subject).claim("realm_access", Map.of("roles", List.of(roles))).build());
    }
}
