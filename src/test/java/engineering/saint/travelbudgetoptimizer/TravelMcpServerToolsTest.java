package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelOption;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptionsResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelMcpServerToolsTest {
    private TravelOptimizationEngine engine;
    private TravelMcpServerTools tools;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        engine = mock(TravelOptimizationEngine.class);
        objectMapper = new ObjectMapper();
        tools = new TravelMcpServerTools(engine, objectMapper);
    }

    @Test
    void registersTwoToolsWithExpectedNamesAndSchemas() {
        List<SyncToolSpecification> specifications =
                SyncMcpAnnotationProviders.toolSpecifications(List.of(tools));

        assertEquals(2, specifications.size());
        SyncToolSpecification optimize = specifications.stream()
                .filter(specification -> specification.tool().name().equals("ottimizzaBudgetViaggio"))
                .findFirst()
                .orElseThrow();
        SyncToolSpecification retrieve = specifications.stream()
                .filter(specification -> specification.tool().name().equals("recuperaRisultatoOttimizzazione"))
                .findFirst()
                .orElseThrow();

        assertEquals(Set.of("partenza", "destinazione", "budgetMassimo"),
                optimize.tool().inputSchema().properties().keySet());
        assertEquals(Set.of("jobId"), retrieve.tool().inputSchema().properties().keySet());
        assertTrue(retrieve.tool().outputSchema().toString().contains("opzioniViaggio"));
    }

    @Test
    void rejectsInvalidInputs() {
        assertError("INVALID_INPUT", () -> tools.ottimizzaBudgetViaggio(" ", "Milano", 100));
        assertError("INVALID_INPUT", () -> tools.ottimizzaBudgetViaggio("Roma", "Milano", -1));
        assertError("INVALID_INPUT", () -> tools.recuperaRisultatoOttimizzazione(" "));
        assertError("INVALID_INPUT", () -> tools.ottimizzaBudgetViaggio("x".repeat(257), "Milano", 100));
    }

    @Test
    void boundsTrackingAndReclaimsExpiredJobs() {
        for (int i = 0; i < TravelOptimizationEngine.MAX_JOBS; i++) {
            startJob();
        }
        assertError("TOO_MANY_REQUESTS", () -> startJob());
        Object tracked = ReflectionTestUtils.getField(tools, "jobStartTimes");
        assertTrue(tracked instanceof Map<?, ?> map && map.size() == TravelOptimizationEngine.MAX_JOBS);
        String expired = ((Map<?, ?>) tracked).keySet().iterator().next().toString();
        setJobStartTime(expired, System.nanoTime() - TravelOptimizationEngine.JOB_RETENTION.toNanos());
        startJob();
        assertFalse(hasTrackedJob(expired));
    }

    @Test
    void surfacesEngineCapacityRejectionWithoutLeakingTrackingEntry() {
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS))
                .when(engine).avviaOttimizzazione(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyDouble());
        assertError("TOO_MANY_REQUESTS", () -> startJob());
        Object tracked = ReflectionTestUtils.getField(tools, "jobStartTimes");
        assertTrue(tracked instanceof Map<?, ?> map && map.isEmpty());
    }
    @Test
    void startsJobAndImmediatelyReturnsItsUniqueId() {
        TravelMcpServerTools.AcceptedOptimization accepted =
                tools.ottimizzaBudgetViaggio("Roma", "Milano", 500);
        TravelMcpServerTools.AcceptedOptimization second =
                tools.ottimizzaBudgetViaggio("Roma", "Milano", 500);

        assertEquals("ACCEPTED", accepted.stato());
        assertNotEquals(accepted.jobId(), second.jobId());
        assertEquals(accepted.jobId(), UUID.fromString(accepted.jobId()).toString());
        verify(engine).avviaOttimizzazione(accepted.jobId(), "Roma", "Milano", 500);
        verify(engine).avviaOttimizzazione(second.jobId(), "Roma", "Milano", 500);
    }

    @Test
    void reportsEngineStartFailuresAsInternalErrors() {
        org.mockito.Mockito.doThrow(new IllegalStateException("executor unavailable"))
                .when(engine).avviaOttimizzazione(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyDouble());

        assertError("INTERNAL_ERROR", () -> tools.ottimizzaBudgetViaggio("Roma", "Milano", 500));
        Object tracked = ReflectionTestUtils.getField(tools, "jobStartTimes");
        assertTrue(tracked instanceof Map<?, ?> map && map.isEmpty());
    }

    @Test
    void returnsNonterminalJobStatusWithoutWaiting() {
        String jobId = startJob();
        when(engine.getStatoAvanzamento(jobId)).thenReturn(Optional.of("Fase 2: Analisi costi completata"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.empty());

        TravelMcpServerTools.OptimizationResult result = tools.recuperaRisultatoOttimizzazione(jobId);

        assertEquals("Fase 2: Analisi costi completata", result.stato());
        assertEquals(jobId, result.jobId());
        assertNull(result.opzioniViaggio());
    }

    @Test
    void returnsCompletedAlternativesAsStructuredFields() throws Exception {
        String jobId = startJob();
        TravelOptionsResponse response = new TravelOptionsResponse(List.of(
                new TravelOption("Treno", 100, "3h", "Roma-Milano"),
                new TravelOption("Bus", 40, "5h", "Roma-Milano"),
                new TravelOption("Aereo", 150, "1h", "Roma-Milano")));
        when(engine.getStatoAvanzamento(jobId)).thenReturn(Optional.of(
                "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.of(objectMapper.writeValueAsString(response)));

        TravelMcpServerTools.OptimizationResult result = tools.recuperaRisultatoOttimizzazione(jobId);

        assertEquals("COMPLETED", result.stato());
        assertEquals(jobId, result.jobId());
        assertEquals(response.opzioniViaggio(), result.opzioniViaggio());
        assertFalse(hasTrackedJob(jobId));

        TravelMcpServerTools.OptimizationResult retry = tools.recuperaRisultatoOttimizzazione(jobId);

        assertEquals(result, retry);
    }

    @Test
    void recognizesCompletionBetweenStatusAndPayloadReads() throws Exception {
        String jobId = startJob();
        TravelOptionsResponse response = new TravelOptionsResponse(List.of(
                new TravelOption("Treno", 100, "3h", "Roma-Milano"),
                new TravelOption("Bus", 40, "5h", "Roma-Milano"),
                new TravelOption("Aereo", 150, "1h", "Roma-Milano")));
        when(engine.getStatoAvanzamento(jobId)).thenReturn(
                Optional.of("Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres in corso"),
                Optional.of("Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.of(objectMapper.writeValueAsString(response)));

        TravelMcpServerTools.OptimizationResult result = tools.recuperaRisultatoOttimizzazione(jobId);

        assertEquals("COMPLETED", result.stato());
        assertEquals(response.opzioniViaggio(), result.opzioniViaggio());
        verify(engine, times(2)).getStatoAvanzamento(jobId);
    }

    @Test
    void reportsUnknownJobIds() {
        assertError("UNKNOWN_JOB", () -> tools.recuperaRisultatoOttimizzazione("unknown"));
    }

    @Test
    void reportsTimeoutAndAllowsExplicitRetry() {
        String jobId = startJob();
        setJobStartTime(jobId, System.nanoTime() - Duration.ofSeconds(121).toNanos());
        when(engine.getStatoAvanzamento(jobId)).thenReturn(Optional.of("Fase 1: Mappatura itinerario avviata"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.empty());

        assertError("TIMEOUT", () -> tools.recuperaRisultatoOttimizzazione(jobId));
        assertTrue(hasTrackedJob(jobId));
    }

    @Test
    void reportsInterruptedRetrieval() {
        String jobId = startJob();
        Thread.currentThread().interrupt();
        try {
            assertError("INTERRUPTED", () -> tools.recuperaRisultatoOttimizzazione(jobId));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reportsCompletedJobWithoutPayload() {
        String jobId = startJob();
        when(engine.getStatoAvanzamento(jobId)).thenReturn(Optional.of(
                "Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.empty());

        assertError("COMPLETED_WITHOUT_PAYLOAD", () -> tools.recuperaRisultatoOttimizzazione(jobId));
    }

    @Test
    void reportsEngineFailuresAsInternalErrors() {
        String jobId = startJob();
        when(engine.getStatoAvanzamento(jobId)).thenThrow(new IllegalStateException("database unavailable"));

        assertError("INTERNAL_ERROR", () -> tools.recuperaRisultatoOttimizzazione(jobId));
    }

    @Test
    void reportsEngineFailureStateAsInternalError() {
        String jobId = startJob();
        when(engine.getStatoAvanzamento(jobId)).thenReturn(Optional.of("Ottimizzazione non riuscita"));
        when(engine.getPayloadFinale(jobId)).thenReturn(Optional.empty());

        assertError("INTERNAL_ERROR", () -> tools.recuperaRisultatoOttimizzazione(jobId));
    }

    private String startJob() {
        return tools.ottimizzaBudgetViaggio("Roma", "Milano", 500).jobId();
    }

    private void assertError(String code, Runnable action) {
        RuntimeException exception = assertThrows(RuntimeException.class, action::run);
        assertTrue(exception.getMessage().startsWith(code + ":"));
    }

    private boolean hasTrackedJob(String jobId) {
        Object tracked = ReflectionTestUtils.getField(tools, "jobStartTimes");
        return tracked instanceof Map<?, ?> map && map.containsKey(jobId);
    }

    @SuppressWarnings("unchecked")
    private void setJobStartTime(String jobId, long startTime) {
        ConcurrentMap<String, Long> starts =
                (ConcurrentMap<String, Long>) ReflectionTestUtils.getField(tools, "jobStartTimes");
        starts.put(jobId, startTime);
    }
}
