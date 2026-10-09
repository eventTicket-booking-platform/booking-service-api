package com.ec7205.event_hub.booking_service_api.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConcurrentAttempts {
    private ConcurrentAttempts() {}

    static <T> List<T> run(int count, IntFunction<T> attempt) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new TimeoutException("Concurrent start timed out");
                    }
                    return attempt.apply(index);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();
            List<T> results = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Workers did not stop");
        }
    }
}
