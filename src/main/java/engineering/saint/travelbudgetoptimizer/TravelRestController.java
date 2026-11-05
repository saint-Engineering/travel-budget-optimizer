package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptimizationRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/viaggi")
public class TravelRestController {
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;
    private static final long POLLING_MILLIS = 750L;

    private final TravelOptimizationEngine travelOptimizationEngine;
    private final ObjectMapper objectMapper;

    public TravelRestController(TravelOptimizationEngine travelOptimizationEngine, ObjectMapper objectMapper) {
        this.travelOptimizationEngine = travelOptimizationEngine;
        this.objectMapper = objectMapper;
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
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicReference<String> lastStatus = new AtomicReference<>(null);

        emitter.onCompletion(() -> scheduler.shutdownNow());
        emitter.onTimeout(() -> {
            safeSend(emitter, SseEmitter.event().name("timeout").data("Stream SSE scaduto"));
            if (streamClosed.compareAndSet(false, true)) {
                scheduler.shutdownNow();
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            if (streamClosed.compareAndSet(false, true)) {
                scheduler.shutdownNow();
            }
        });

        scheduler.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }

            try {
                Optional<String> statoCorrente = travelOptimizationEngine.getStatoAvanzamento(jobId);
                if (statoCorrente.isPresent() && !Objects.equals(lastStatus.get(), statoCorrente.get())) {
                    lastStatus.set(statoCorrente.get());
                    safeSend(emitter, SseEmitter.event().name("status").data(statoCorrente.get()));
                }

                Optional<String> payloadFinale = travelOptimizationEngine.getPayloadFinale(jobId);
                if (payloadFinale.isPresent()) {
                    JsonNode result = objectMapper.readTree(payloadFinale.get());
                    safeSend(emitter, SseEmitter.event().name("result").data(result));
                    if (streamClosed.compareAndSet(false, true)) {
                        scheduler.shutdownNow();
                        emitter.complete();
                    }
                }
            } catch (Exception exception) {
                if (streamClosed.compareAndSet(false, true)) {
                    scheduler.shutdownNow();
                    emitter.completeWithError(exception);
                }
            }
        }, 0L, POLLING_MILLIS, TimeUnit.MILLISECONDS);

        return emitter;
    }

    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to send SSE event", exception);
        }
    }
}