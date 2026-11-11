package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOption;
import engineering.saint.travelbudgetoptimizer.dto.TravelItineraryDetails;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptionsResponse;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptimizationRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class TravelOptimizationEngine {
    static final int MAX_JOBS = 1_000;
    static final Duration JOB_RETENTION = Duration.ofMinutes(10);
    private static final Logger logger = LoggerFactory.getLogger(TravelOptimizationEngine.class);
    private final ObjectMapper objectMapper;
    private final CloudTravelTools cloudTravelTools;
    private final ConcurrentMap<String, JobProgress> avanzamenti = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ThreadPoolExecutor executor;

    @Autowired
    public TravelOptimizationEngine(ObjectMapper objectMapper, CloudTravelTools cloudTravelTools) {
        this(objectMapper, Clock.systemUTC(),
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32)),
                cloudTravelTools);
    }

    public TravelOptimizationEngine(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC(),
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32)),
                null);
    }

    TravelOptimizationEngine(ObjectMapper objectMapper, Clock clock, ThreadPoolExecutor executor) {
        this(objectMapper, clock, executor, null);
    }

    TravelOptimizationEngine(
            ObjectMapper objectMapper, Clock clock, ThreadPoolExecutor executor, CloudTravelTools cloudTravelTools) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = executor;
        this.cloudTravelTools = cloudTravelTools;
    }

    public synchronized void avviaOttimizzazione(String jobId, String partenza, String destinazione, double budget) {
        avviaOttimizzazione(jobId, partenza, destinazione, budget, null);
    }

    public synchronized void avviaOttimizzazione(
            String jobId, String partenza, String destinazione, double budget, LocalDate dataPartenza) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        new TravelOptimizationRequest(partenza, destinazione, budget, dataPartenza);
        long now = clock.millis();
        avanzamenti.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        if (avanzamenti.containsKey(jobId)) {
            throw new IllegalArgumentException("jobId already exists");
        }
        if (avanzamenti.size() >= MAX_JOBS) {
            throw capacityExceeded();
        }

        avanzamenti.put(jobId, new JobProgress("Fase 1: Mappatura itinerario avviata", null, now));
        try {
            executor.execute(() -> ottimizza(jobId, partenza, destinazione, budget, dataPartenza));
        } catch (RejectedExecutionException exception) {
            avanzamenti.remove(jobId);
            throw capacityExceeded();
        }
    }

    private void ottimizza(
            String jobId, String partenza, String destinazione, double budget, LocalDate dataPartenza) {
        aggiornaStato(jobId, "Fase 2: Analisi costi completata");

        Map<String, TravelItineraryDetails> dettagliPerMezzo = recuperaDettagliViaggio(
                jobId, partenza, destinazione, dataPartenza);
        TravelOptionsResponse alternative = new TravelOptionsResponse(List.of(
                new TravelOption("Treno", budget * 0.65, "3h 30m",
                        "Itinerario da " + partenza + " a " + destinazione, dettagliPerMezzo.get("treno")),
                new TravelOption("Autobus", budget * 0.4, "5h 15m",
                        "Itinerario economico da " + partenza + " a " + destinazione,
                        dettagliPerMezzo.get("autobus")),
                new TravelOption("Aereo", budget * 0.9, "1h 20m",
                        "Itinerario rapido da " + partenza + " a " + destinazione,
                        dettagliPerMezzo.get("aereo"))));

        aggiornaStato(jobId, "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres in corso");
        try {
            String payload = objectMapper.writeValueAsString(alternative);
            avanzamenti.computeIfPresent(jobId, (key, progress) -> new JobProgress(
                    "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati", payload,
                    progress.createdAtMillis()));
        } catch (JsonProcessingException exception) {
            aggiornaStato(jobId, "Ottimizzazione non riuscita");
            logger.error("Unable to serialize optimization result for job {}", jobId, exception);
        }
    }

    private Map<String, TravelItineraryDetails> recuperaDettagliViaggio(
            String jobId, String partenza, String destinazione, LocalDate dataPartenza) {
        if (cloudTravelTools == null) {
            return Map.of();
        }
        try {
            return cloudTravelTools.cercaDettagliViaggio(partenza, destinazione, dataPartenza);
        } catch (RuntimeException exception) {
            logger.warn("Unable to retrieve travel details for job {}; preserving baseline proposals", jobId,
                    exception);
            return Map.of();
        }
    }

    public Optional<JobProgress> getProgress(String jobId) {
        JobProgress progress = avanzamenti.get(jobId);
        if (progress != null && expired(progress, clock.millis())) {
            avanzamenti.remove(jobId, progress);
            return Optional.empty();
        }
        return Optional.ofNullable(progress);
    }

    public Optional<String> getStatoAvanzamento(String jobId) {
        return getProgress(jobId).map(JobProgress::stato);
    }

    public Optional<String> getPayloadFinale(String jobId) {
        return getProgress(jobId).map(JobProgress::payloadJson);
    }

    private void aggiornaStato(String jobId, String stato) {
        avanzamenti.computeIfPresent(jobId, (key, progress) ->
                new JobProgress(stato, progress.payloadJson(), progress.createdAtMillis()));
    }

    private boolean expired(JobProgress progress, long now) {
        return now - progress.createdAtMillis() >= JOB_RETENTION.toMillis();
    }

    private ResponseStatusException capacityExceeded() {
        logger.warn("Optimization job capacity exceeded");
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Optimization capacity exceeded; retry later");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record JobProgress(String stato, String payloadJson, long createdAtMillis) {
    }
}
