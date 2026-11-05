package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TravelOptimizationEngineTest {
    @Autowired
    private TravelOptimizationEngine engine;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void asynchronouslyStoresProgressAndThreeTravelAlternativesByJobId() throws Exception {
        String jobId = "test-job";
        engine.avviaOttimizzazione(jobId, "Roma", "Milano", 500);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (engine.getPayloadFinale(jobId).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }

        assertEquals("Fase 3: Filtraggio e salvataggio sul DB Cloud Neon Postgres completati",
                engine.getStatoAvanzamento(jobId).orElseThrow());
        JsonNode payload = objectMapper.readTree(engine.getPayloadFinale(jobId).orElseThrow());
        assertEquals(3, payload.get("opzioniViaggio").size());
        assertTrue(payload.get("opzioniViaggio").get(0).get("descrizioneDettagliata").asText().contains("Roma"));
    }
}
