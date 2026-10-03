package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.entity.IdempotencyRecord;
import com.ec7205.event_hub.booking_service_api.enums.IdempotencyStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class IdempotencyRecordRepositoryTest {

    @Autowired
    private IdempotencyRecordRepository repository;

    @Test
    void shouldAllowOnlyOneConcurrentClaimForSameUserAndKey()
            throws Exception {

        int threadCount = 2;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =
                new CountDownLatch(threadCount);

        CountDownLatch start =
                new CountDownLatch(1);

        Callable<Boolean> task = () -> {

            ready.countDown();
            start.await();

            try {
                IdempotencyRecord record =
                        IdempotencyRecord.builder()
                                .userId("user-1")
                                .idempotencyKey("same-key")
                                .requestFingerprint(
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                )
                                .status(IdempotencyStatus.PROCESSING)
                                .build();

                repository.saveAndFlush(record);

                return true;

            } catch (DataIntegrityViolationException ex) {
                return false;
            }
        };

        Future<Boolean> first =
                executor.submit(task);

        Future<Boolean> second =
                executor.submit(task);

        ready.await();
        start.countDown();

        List<Boolean> results =
                List.of(
                        first.get(),
                        second.get()
                );

        executor.shutdown();

        long successCount =
                results.stream()
                        .filter(Boolean::booleanValue)
                        .count();

        assertEquals(1, successCount);

        assertEquals(
                1,
                repository
                        .findByUserIdAndIdempotencyKey(
                                "user-1",
                                "same-key"
                        )
                        .stream()
                        .count()
        );
    }
}