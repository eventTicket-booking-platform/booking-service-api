package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.entity.OutboxEvent;
import com.ec7205.event_hub.booking_service_api.service.OutboxClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "eventhub.outbox.processing-timeout=PT2M")
@ActiveProfiles("test")
@Import(OutboxClaimService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRecoveryTest {

    @Autowired private OutboxEventRepository repository;
    @Autowired private OutboxClaimService claimService;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void recoveryResetsOnlyProcessingRowsStrictlyOlderThanCutoff() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 1, 1, 12, 0);
        OutboxEvent stale = save("PROCESSING", cutoff.minusSeconds(1));
        List<OutboxEvent> untouched = List.of(
                save("PROCESSING", cutoff),
                save("PROCESSING", cutoff.plusSeconds(1)),
                save("PROCESSING", null),
                save("PENDING", cutoff.minusHours(1)),
                save("SENT", cutoff.minusHours(1)),
                save("FAILED", cutoff.minusHours(1)));

        Integer recoveredCount = transactionTemplate.execute(
                status -> repository.recoverStaleProcessingEvents(cutoff));
        assertEquals(Integer.valueOf(1), recoveredCount);

        OutboxEvent recovered = repository.findById(stale.getId()).orElseThrow();
        assertEquals("PENDING", recovered.getStatus());
        assertNull(recovered.getProcessingStartedAt());
        assertEquals(stale.getVersion() + 1, recovered.getVersion());
        assertEquals(stale.getRetryCount(), recovered.getRetryCount());
        assertEquals(stale.getPayload(), recovered.getPayload());
        assertEquals(stale.getCorrelationId(), recovered.getCorrelationId());
        for (OutboxEvent original : untouched) {
            OutboxEvent actual = repository.findById(original.getId()).orElseThrow();
            assertEquals(original.getStatus(), actual.getStatus());
            assertEquals(original.getProcessingStartedAt(), actual.getProcessingStartedAt());
            assertEquals(original.getVersion(), actual.getVersion());
        }
        Integer repeatedRecoveryCount = transactionTemplate.execute(
                status -> repository.recoverStaleProcessingEvents(cutoff));
        assertEquals(Integer.valueOf(0), repeatedRecoveryCount);
    }

    @Test
    void claimingRecoversAbandonedRowsButLeavesFreshClaimsAlone() {
        // Three minutes old proves that the configured two-minute timeout is used.
        OutboxEvent stale = save("PROCESSING", LocalDateTime.now().minusMinutes(3).withNano(0));
        OutboxEvent fresh = save("PROCESSING", LocalDateTime.now().withNano(0));

        List<OutboxEvent> claimed = claimService.claimPendingEvents();

        assertEquals(List.of(stale.getId()), claimed.stream().map(OutboxEvent::getId).toList());
        OutboxEvent reclaimed = repository.findById(stale.getId()).orElseThrow();
        assertEquals("PROCESSING", reclaimed.getStatus());
        assertTrue(reclaimed.getProcessingStartedAt().isAfter(stale.getProcessingStartedAt()));
        assertEquals(1, reclaimed.getRetryCount());
        assertEquals(fresh.getProcessingStartedAt(),
                repository.findById(fresh.getId()).orElseThrow().getProcessingStartedAt());
        assertTrue(claimService.claimPendingEvents().isEmpty());
    }

    @Test
    void lateWorkerCannotOverwriteReclaimedOrCompletedEvent() {
        OutboxEvent oldWorker = save("PROCESSING", LocalDateTime.now().minusMinutes(3));
        OutboxEvent newWorker = claimService.claimPendingEvents().get(0);

        oldWorker.setStatus("SENT");
        oldWorker.setProcessingStartedAt(null);
        assertThrows(OptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(oldWorker));
        assertEquals("PROCESSING", repository.findById(newWorker.getId()).orElseThrow().getStatus());

        newWorker.setStatus("SENT");
        newWorker.setProcessingStartedAt(null);
        newWorker.setPublishedAt(LocalDateTime.now());
        repository.saveAndFlush(newWorker);

        oldWorker.setStatus("FAILED");
        assertThrows(OptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(oldWorker));
        assertEquals("SENT", repository.findById(newWorker.getId()).orElseThrow().getStatus());
    }

    private OutboxEvent save(String status, LocalDateTime startedAt) {
        return repository.saveAndFlush(OutboxEvent.builder()
                .eventType("BOOKING_CONFIRMED")
                .aggregateType("BOOKING")
                .aggregateId("BK-RECOVERY")
                .payload("{\"bookingId\":\"BK-RECOVERY\"}")
                .correlationId("recovery-test")
                .status(status)
                .retryCount(1)
                .processingStartedAt(startedAt)
                .build());
    }
}
