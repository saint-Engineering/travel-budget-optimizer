package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptimizationRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/viaggi")
public class TravelRestController {
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;
    private static final long POLLING_MILLIS = 750L;
    static final int MAX_STREAMS = 100;
    private static final Logger logger = LoggerFactory.getLogger(TravelRestController.class);

    private final TravelOptimizationEngine travelOptimizationEngine;
    private final ObjectMapper objectMapper;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);
    private final Semaphore streamSlots = new Semaphore(MAX_STREAMS);

    public TravelRestController(TravelOptimizationEngine travelOptimizationEngine, ObjectMapper objectMapper) {
        this.travelOptimizationEngine = travelOptimizationEngine;
        this.objectMapper = objectMapper;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @PostMapping("/ottimizza")
    public ResponseEntity<Map<String, String>> ottimizza(@RequestBody TravelOptimizationRequest request) {
        String jobId = UUID.randomUUID().toString();
        travelOptimizationEngine.avviaOttimizzazione(
                jobId,
                request.partenza(),
                request.destinazione(),
                request.budgetMassimo());
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping(path = "/status/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter status(@PathVariable String jobId) {
        if (travelOptimizationEngine.getProgress(jobId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired job");
        }
        if (!streamSlots.tryAcquire()) {
            logger.warn("SSE connection capacity exceeded");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many SSE connections");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> polling = new AtomicReference<>();
        AtomicReference<String> lastStatus = new AtomicReference<>(null);
        Runnable cleanup = () -> {
            if (streamClosed.compareAndSet(false, true)) {
                ScheduledFuture<?> task = polling.get();
                if (task != null) {
                    task.cancel(false);
                }
                streamSlots.release();
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            try {
                safeSend(emitter, SseEmitter.event().name("timeout").data("Stream SSE scaduto"));
            } finally {
                cleanup.run();
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            logger.debug("SSE connection failed for job {}", jobId, error);
            cleanup.run();
        });

        Runnable poll = () -> {
            if (streamClosed.get()) {
                return;
            }

            try {
                Optional<TravelOptimizationEngine.JobProgress> progress = travelOptimizationEngine.getProgress(jobId);
                if (progress.isEmpty()) {
                    safeSend(emitter, SseEmitter.event().name("timeout").data("Job scaduto"));
                    cleanup.run();
                    emitter.complete();
                    return;
                }
                String statoCorrente = progress.get().stato();
                if (!Objects.equals(lastStatus.get(), statoCorrente)) {
                    lastStatus.set(statoCorrente);
                    safeSend(emitter, SseEmitter.event().name("status").data(statoCorrente));
                }

                String payloadFinale = progress.get().payloadJson();
                if (payloadFinale != null) {
                    JsonNode result = objectMapper.readTree(payloadFinale);
                    safeSend(emitter, SseEmitter.event().name("result").data(result));
                    cleanup.run();
                    emitter.complete();
                }
            } catch (IOException | RuntimeException exception) {
                logger.warn("Unable to stream optimization result for job {}", jobId, exception);
                cleanup.run();
                emitter.completeWithError(exception);
            }
        };
        try {
            ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(poll, 0L, POLLING_MILLIS, TimeUnit.MILLISECONDS);
            polling.set(task);
            if (streamClosed.get()) {
                task.cancel(false);
            }
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            cleanup.run();
            logger.warn("SSE scheduler unavailable", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE scheduler unavailable");
        }

        return emitter;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to send SSE event", exception);
        }
    }
}