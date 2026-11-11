package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptimizationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobCapacityTest {
    private Clock clock;
    private ThreadPoolExecutor executor;
    private TravelOptimizationEngine engine;

    @BeforeEach
    void setUp() {
        clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        executor = mock(ThreadPoolExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        engine = new TravelOptimizationEngine(new ObjectMapper(), clock, executor);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void rejectsJobsBeyondCapacityAndReclaimsExpiredEntries() {
        for (int i = 0; i < TravelOptimizationEngine.MAX_JOBS; i++) {
            engine.avviaOttimizzazione("job-" + i, "Roma", "Milano", 100);
        }
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> engine.avviaOttimizzazione("overflow", "Roma", "Milano", 100));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertTrue(engine.getProgress("overflow").isEmpty());

        when(clock.millis()).thenReturn(1_000L + TravelOptimizationEngine.JOB_RETENTION.toMillis());
        engine.avviaOttimizzazione("replacement", "Roma", "Milano", 100);
        assertTrue(engine.getProgress("job-0").isEmpty());
        assertTrue(engine.getPayloadFinale("replacement").isPresent());
    }

    @Test
    void expiresResultsAtExactRetentionBoundary() {
        engine.avviaOttimizzazione("job", "Roma", "Milano", 100);
        long boundary = 1_000L + TravelOptimizationEngine.JOB_RETENTION.toMillis();
        when(clock.millis()).thenReturn(boundary - 1);
        assertTrue(engine.getPayloadFinale("job").isPresent());
        when(clock.millis()).thenReturn(boundary);
        assertTrue(engine.getProgress("job").isEmpty());
        assertTrue(engine.getStatoAvanzamento("job").isEmpty());
        assertTrue(engine.getPayloadFinale("job").isEmpty());
    }

    @Test
    void rollsBackReservationWhenWorkerQueueIsFull() {
        doThrow(new RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> engine.avviaOttimizzazione("rejected", "Roma", "Milano", 100));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertTrue(engine.getProgress("rejected").isEmpty());
    }

    @Test
    void productionExecutorBoundsWorkersAndQueuedJobs() throws Exception {
        TravelOptimizationEngine bounded = new TravelOptimizationEngine(new ObjectMapper());
        ThreadPoolExecutor workers = (ThreadPoolExecutor) ReflectionTestUtils.getField(bounded, "executor");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                workers.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) {
                bounded.avviaOttimizzazione("queued-" + i, "Roma", "Milano", 100);
            }
            assertEquals(2, workers.getMaximumPoolSize());
            assertEquals(32, workers.getQueue().size());
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> bounded.avviaOttimizzazione("overflow", "Roma", "Milano", 100));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
            assertTrue(bounded.getProgress("overflow").isEmpty());
        } finally {
            release.countDown();
            bounded.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void delayedWorkerCannotRestoreExpiredJob() {
        AtomicReference<Runnable> work = new AtomicReference<>();
        doAnswer(invocation -> {
            work.set(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(executor).execute(any(Runnable.class));
        engine.avviaOttimizzazione("delayed", "Roma", "Milano", 100);
        when(clock.millis()).thenReturn(1_000L + TravelOptimizationEngine.JOB_RETENTION.toMillis());
        assertTrue(engine.getProgress("delayed").isEmpty());
        work.get().run();
        assertTrue(engine.getProgress("delayed").isEmpty());
    }

    @Test
    void acceptsMaximumLocationLengthAndRejectsOversizedLocations() {
        String maximum = "x".repeat(TravelOptimizationRequest.MAX_LOCATION_LENGTH);
        engine.avviaOttimizzazione("maximum", maximum, maximum, 100);
        assertTrue(engine.getPayloadFinale("maximum").isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> engine.avviaOttimizzazione("oversized", maximum + "x", "Milano", 100));
        assertThrows(IllegalArgumentException.class,
                () -> new TravelOptimizationRequest("Roma", maximum + "x", 100));
        assertTrue(engine.getProgress("oversized").isEmpty());
    }
}
