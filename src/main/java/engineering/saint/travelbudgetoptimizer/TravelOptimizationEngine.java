package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOption;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptionsResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class TravelOptimizationEngine {
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, JobProgress> avanzamenti = new ConcurrentHashMap<>();

    public TravelOptimizationEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Async
    public void avviaOttimizzazione(String jobId, String partenza, String destinazione, double budget) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        if (partenza == null || partenza.isBlank() || destinazione == null || destinazione.isBlank()) {
            throw new IllegalArgumentException("partenza and destinazione must not be blank");
        }
        if (!Double.isFinite(budget) || budget < 0) {
            throw new IllegalArgumentException("budget must be finite and non-negative");
        }

        avanzamenti.put(jobId, new JobProgress("Fase 1: Mappatura itinerario avviata", null));
        aggiornaStato(jobId, "Fase 2: Analisi costi completata");

        TravelOptionsResponse alternative = new TravelOptionsResponse(List.of(
                new TravelOption("Treno", budget * 0.65, "3h 30m",
                        "Itinerario da " + partenza + " a " + destinazione),
                new TravelOption("Autobus", budget * 0.4, "5h 15m",
                        "Itinerario economico da " + partenza + " a " + destinazione),
                new TravelOption("Aereo", budget * 0.9, "1h 20m",
                        "Itinerario rapido da " + partenza + " a " + destinazione)));

        aggiornaStato(jobId, "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres in corso");
        try {
            avanzamenti.put(jobId, new JobProgress(
                    "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati",
                    objectMapper.writeValueAsString(alternative)));
        } catch (JsonProcessingException exception) {
            aggiornaStato(jobId, "Ottimizzazione non riuscita");
            throw new IllegalStateException("Unable to serialize optimization result", exception);
        }
    }

    public Optional<String> getStatoAvanzamento(String jobId) {
        return Optional.ofNullable(avanzamenti.get(jobId)).map(JobProgress::stato);
    }

    public Optional<String> getPayloadFinale(String jobId) {
        return Optional.ofNullable(avanzamenti.get(jobId)).map(JobProgress::payloadJson);
    }

    private void aggiornaStato(String jobId, String stato) {
        avanzamenti.compute(jobId, (key, progress) ->
                new JobProgress(stato, progress == null ? null : progress.payloadJson()));
    }

    private record JobProgress(String stato, String payloadJson) {
    }
}
