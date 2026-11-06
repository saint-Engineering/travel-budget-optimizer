package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOption;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptionsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TravelMcpServerTools {
    private static final Logger logger = LoggerFactory.getLogger(TravelMcpServerTools.class);
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(120);
    private static final String COMPLETED_ENGINE_STATUS =
            "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati";
    private static final String FAILED_ENGINE_STATUS = "Ottimizzazione non riuscita";
    private static final List<String> IN_PROGRESS_ENGINE_STATUSES = List.of(
            "Fase 1: Mappatura itinerario avviata",
            "Fase 2: Analisi costi completata",
            "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres in corso");

    private final TravelOptimizationEngine travelOptimizationEngine;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Long> jobStartTimes = new ConcurrentHashMap<>();

    public TravelMcpServerTools(TravelOptimizationEngine travelOptimizationEngine, ObjectMapper objectMapper) {
        this.travelOptimizationEngine = travelOptimizationEngine;
        this.objectMapper = objectMapper;
    }

    @McpTool(
            name = "ottimizzaBudgetViaggio",
            description = "Avvia in modo asincrono un'ottimizzazione di viaggio e restituisce il job ID.",
            generateOutputSchema = true)
    public AcceptedOptimization ottimizzaBudgetViaggio(
            @McpToolParam(description = "Città o località di partenza", required = true) String partenza,
            @McpToolParam(description = "Città o località di destinazione", required = true) String destinazione,
            @McpToolParam(description = "Budget massimo del viaggio, finito e non negativo", required = true)
            double budgetMassimo) {
        validaInput(partenza, destinazione, budgetMassimo);

        String jobId = UUID.randomUUID().toString();
        jobStartTimes.put(jobId, System.nanoTime());
        try {
            travelOptimizationEngine.avviaOttimizzazione(jobId, partenza, destinazione, budgetMassimo);
        } catch (RuntimeException exception) {
            jobStartTimes.remove(jobId);
            throw erroreInterno(jobId, "avvio dell'ottimizzazione", exception);
        }

        return new AcceptedOptimization(jobId, "ACCEPTED");
    }

    @McpTool(
            name = "recuperaRisultatoOttimizzazione",
            description = "Recupera lo stato o il risultato di un job di ottimizzazione tramite il suo job ID.",
            generateOutputSchema = true)
    public OptimizationResult recuperaRisultatoOttimizzazione(
            @McpToolParam(description = "Job ID restituito da ottimizzaBudgetViaggio", required = true)
            String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("INVALID_INPUT: jobId deve essere valorizzato.");
        }

        Long startedAt = jobStartTimes.get(jobId);
        if (startedAt == null && Thread.currentThread().isInterrupted()) {
            throw new IllegalArgumentException("UNKNOWN_JOB: nessun job trovato per il job ID indicato.");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("INTERRUPTED: la richiesta di recupero è stata interrotta.");
        }

        Optional<String> status;
        Optional<String> payload;
        try {
            status = travelOptimizationEngine.getStatoAvanzamento(jobId);
            payload = travelOptimizationEngine.getPayloadFinale(jobId);
            if (payload.isPresent() && !COMPLETED_ENGINE_STATUS.equals(status.orElse(null))) {
                status = travelOptimizationEngine.getStatoAvanzamento(jobId);
            }
        } catch (RuntimeException exception) {
            throw erroreInterno(jobId, "lettura dello stato o del risultato", exception);
        }

        String engineStatus = status.orElse(null);
        if (startedAt == null
                && (!COMPLETED_ENGINE_STATUS.equals(engineStatus) || payload.isEmpty())) {
            throw new IllegalArgumentException("UNKNOWN_JOB: nessun job trovato per il job ID indicato.");
        }
        if (FAILED_ENGINE_STATUS.equals(engineStatus)) {
            jobStartTimes.remove(jobId);
            logger.error("Il motore ha segnalato un errore per il job MCP {}: stato={}", jobId, engineStatus);
            throw new IllegalStateException("INTERNAL_ERROR: il motore non è riuscito a completare il job.");
        }

        if (COMPLETED_ENGINE_STATUS.equals(engineStatus)) {
            if (payload.isEmpty()) {
                jobStartTimes.remove(jobId);
                logger.error("Il job MCP {} risulta completato, ma non contiene il payload finale.", jobId);
                throw new IllegalStateException(
                        "COMPLETED_WITHOUT_PAYLOAD: il job è terminato senza un risultato.");
            }
            return risultatoCompletato(jobId, payload.get());
        }

        if (payload.isPresent()) {
            jobStartTimes.remove(jobId);
            logger.error("Il job MCP {} ha un payload senza uno stato terminale riconosciuto: stato={}",
                    jobId, engineStatus);
            throw new IllegalStateException("INTERNAL_ERROR: stato e risultato del job non sono coerenti.");
        }

        if (engineStatus != null && !IN_PROGRESS_ENGINE_STATUSES.contains(engineStatus)) {
            jobStartTimes.remove(jobId);
            logger.error("Il motore ha restituito uno stato non riconosciuto per il job MCP {}: stato={}",
                    jobId, engineStatus);
            throw new IllegalStateException("INTERNAL_ERROR: il motore ha restituito uno stato non riconosciuto.");
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        if (elapsedNanos >= JOB_TIMEOUT.toNanos()) {
            logger.warn("Timeout di {} secondi per il job MCP {} ancora in corso.", JOB_TIMEOUT.toSeconds(), jobId);
            throw new IllegalStateException("TIMEOUT: il job non è terminato entro "
                    + JOB_TIMEOUT.toSeconds() + " secondi; riprovare il recupero.");
        }

        return new OptimizationResult(jobId, engineStatus == null ? "ACCEPTED" : engineStatus, null);
    }

    private OptimizationResult risultatoCompletato(String jobId, String payloadJson) {
        try {
            TravelOptionsResponse response = objectMapper.readValue(payloadJson, TravelOptionsResponse.class);
            jobStartTimes.remove(jobId);
            return new OptimizationResult(jobId, "COMPLETED", response.opzioniViaggio());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw erroreInterno(jobId, "deserializzazione del payload finale", exception);
        }
    }

    private static void validaInput(String partenza, String destinazione, double budgetMassimo) {
        if (partenza == null || partenza.isBlank()) {
            throw new IllegalArgumentException("INVALID_INPUT: partenza deve essere valorizzata.");
        }
        if (destinazione == null || destinazione.isBlank()) {
            throw new IllegalArgumentException("INVALID_INPUT: destinazione deve essere valorizzata.");
        }
        if (!Double.isFinite(budgetMassimo) || budgetMassimo < 0) {
            throw new IllegalArgumentException("INVALID_INPUT: budgetMassimo deve essere finito e non negativo.");
        }
    }

    private IllegalStateException erroreInterno(String jobId, String operation, RuntimeException exception) {
        logger.error("Errore interno MCP durante {} per il job {}.", operation, jobId, exception);
        return new IllegalStateException("INTERNAL_ERROR: errore interno durante l'elaborazione del job.");
    }

    private IllegalStateException erroreInterno(String jobId, String operation, Exception exception) {
        logger.error("Errore interno MCP durante {} per il job {}.", operation, jobId, exception);
        return new IllegalStateException("INTERNAL_ERROR: errore interno durante l'elaborazione del job.");
    }

    public record AcceptedOptimization(String jobId, String stato) {
    }

    public record OptimizationResult(String jobId, String stato, List<TravelOption> opzioniViaggio) {
        public OptimizationResult {
            opzioniViaggio = opzioniViaggio == null ? null : List.copyOf(opzioniViaggio);
        }
    }
}
