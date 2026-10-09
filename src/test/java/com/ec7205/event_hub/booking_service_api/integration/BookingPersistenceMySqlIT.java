package com.ec7205.event_hub.booking_service_api.integration;

import com.ec7205.event_hub.booking_service_api.entity.*;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import com.ec7205.event_hub.booking_service_api.enums.IdempotencyStatus;
import com.ec7205.event_hub.booking_service_api.repository.*;
import com.ec7205.event_hub.booking_service_api.service.OutboxClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Import(OutboxClaimService.class)
@TestPropertySource(properties = "eventhub.outbox.processing-timeout=PT2M")
class BookingPersistenceMySqlIT extends MySqlIntegrationTest {
    @Autowired private BookingRepository bookings;
    @Autowired private IdempotencyRecordRepository idempotency;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxClaimService claims;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        bookings.deleteAll();
        idempotency.deleteAll();
    }

    @ParameterizedTest(name = "concurrent unique user/key constraint: {0}")
    @ValueSource(strings = {"booking", "idempotency"})
    void onlyOneConcurrentInsertWinsAndKeyIsScopedToUser(String table) throws Exception {
        String constraint = table.equals("booking")
                ? "uk_booking_user_idempotency" : "uk_idempotency_user_key";
        List<Boolean> results = ConcurrentAttempts.run(20, index -> {
            try {
                insert(table, "customer", index);
                return true;
            } catch (DataIntegrityViolationException duplicate) {
                Throwable cause = duplicate;
                while (cause != null && !(cause instanceof SQLException)) {
                    cause = cause.getCause();
                }
                SQLException sql = assertInstanceOf(SQLException.class, cause);
                assertEquals(1062, sql.getErrorCode(), "Only MySQL duplicate-key errors are expected");
                assertTrue(sql.getMessage().contains(constraint), sql.getMessage());
                return false;
            }
        });

        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(19L, results.stream().filter(result -> !result).count());
        assertEquals(1L, table.equals("booking") ? bookings.count() : idempotency.count());
        insert(table, "another-customer", 20);
        assertEquals(2L, table.equals("booking") ? bookings.count() : idempotency.count());
    }

    @Test
    void staleOutboxRecoveryPreservesPayloadAndRejectsLateWorkerWrites() {
        OutboxEvent oldWorker = saveOutbox(LocalDateTime.now().minusHours(1));
        OutboxEvent fresh = saveOutbox(LocalDateTime.now().plusMinutes(1));

        List<OutboxEvent> claimed = claims.claimPendingEvents();
        assertEquals(List.of(oldWorker.getId()), claimed.stream().map(OutboxEvent::getId).toList());
        OutboxEvent newWorker = claimed.get(0);
        OutboxEvent persisted = outbox.findById(newWorker.getId()).orElseThrow();
        assertEquals("PROCESSING", persisted.getStatus());
        assertTrue(persisted.getVersion() > oldWorker.getVersion());
        assertTrue(persisted.getProcessingStartedAt().isAfter(oldWorker.getProcessingStartedAt()));
        assertEquals(oldWorker.getPayload(), persisted.getPayload());
        assertEquals(oldWorker.getCorrelationId(), persisted.getCorrelationId());
        assertEquals(oldWorker.getRetryCount(), persisted.getRetryCount());
        assertEquals(fresh.getVersion(), outbox.findById(fresh.getId()).orElseThrow().getVersion());
        assertTrue(claims.claimPendingEvents().isEmpty());

        oldWorker.setStatus("FAILED");
        assertThrows(OptimisticLockingFailureException.class, () -> outbox.saveAndFlush(oldWorker));
        assertEquals("PROCESSING", outbox.findById(newWorker.getId()).orElseThrow().getStatus());
        newWorker.setStatus("SENT");
        newWorker.setProcessingStartedAt(null);
        newWorker.setPublishedAt(LocalDateTime.now());
        outbox.saveAndFlush(newWorker);
        assertThrows(OptimisticLockingFailureException.class, () -> outbox.saveAndFlush(oldWorker));
        OutboxEvent sent = outbox.findById(newWorker.getId()).orElseThrow();
        assertEquals("SENT", sent.getStatus());
        assertNotNull(sent.getPublishedAt());
        assertNull(sent.getProcessingStartedAt());
    }

    private void insert(String table, String user, int index) {
        if (table.equals("booking")) {
            bookings.saveAndFlush(Booking.builder()
                    .userId(user).idempotencyKey("same-key").requestFingerprint("fingerprint")
                    // A distinct reference ensures only the user/key constraint can reject a worker.
                    .bookingReference("BK-MYSQL-" + index).eventId(1L).eventTitleSnapshot("Event")
                    .eventStartDateTimeSnapshot(LocalDateTime.now().plusDays(1))
                    .status(BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build());
        } else {
            idempotency.saveAndFlush(IdempotencyRecord.builder()
                    .userId(user).idempotencyKey("same-key").requestFingerprint("fingerprint")
                    .status(IdempotencyStatus.PROCESSING).build());
        }
    }

    private OutboxEvent saveOutbox(LocalDateTime startedAt) {
        return outbox.saveAndFlush(OutboxEvent.builder()
                .eventType("BOOKING_CONFIRMED").aggregateType("BOOKING").aggregateId("BK-MYSQL")
                .payload("{\"bookingId\":\"BK-MYSQL\"}").correlationId("mysql-correlation")
                .status("PROCESSING").retryCount(1).processingStartedAt(startedAt).build());
    }
}
