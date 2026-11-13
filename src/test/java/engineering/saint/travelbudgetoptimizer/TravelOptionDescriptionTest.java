package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelItineraryDetails;
import engineering.saint.travelbudgetoptimizer.dto.TravelLeg;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelOptionDescriptionTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void buildsDescriptionsFromMatchingModesAndMultipleLegs() throws Exception {
        TravelItineraryDetails train = new TravelItineraryDetails(List.of(
                new TravelLeg("Roma Termini", "Firenze SMN", "08:00", "09:30", "Frecciarossa",
                        List.of("Orte")),
                new TravelLeg("Firenze SMN", "Milano Centrale", "10:00", "12:00", "Frecciarossa",
                        List.of("Bologna"))), List.of(), true);
        TravelItineraryDetails bus = new TravelItineraryDetails(List.of(
                new TravelLeg("Roma Tiburtina", "Milano Lampugnano", "07:00", "14:00", "BusItalia",
                        List.of("Siena"))), List.of(), true);
        TravelItineraryDetails flight = new TravelItineraryDetails(List.of(
                new TravelLeg("Fiumicino", "Linate", "09:00", "10:10", "VoloAlfa", List.of())),
                List.of(), false);

        JsonNode options = optimize(Map.of("treno", train, "autobus", bus, "aereo", flight));

        assertEquals(3, options.size());
        String trainDescription = options.get(0).path("descrizioneDettagliata").asText();
        assertTrue(trainDescription.contains("Tratta 1"));
        assertTrue(trainDescription.contains("Tratta 2"));
        assertTrue(trainDescription.contains("Roma Termini"));
        assertTrue(trainDescription.contains("Frecciarossa"));
        assertTrue(trainDescription.contains("Orte"));
        assertFalse(trainDescription.contains("Lampugnano"));
        assertFalse(trainDescription.contains("VoloAlfa"));

        String busDescription = options.get(1).path("descrizioneDettagliata").asText();
        assertTrue(busDescription.contains("Milano Lampugnano"));
        assertFalse(busDescription.contains("Frecciarossa"));
        assertFalse(busDescription.contains("Fiumicino"));

        String flightDescription = options.get(2).path("descrizioneDettagliata").asText();
        assertTrue(flightDescription.startsWith("Dettagli disponibili per aereo"));
        assertTrue(flightDescription.contains("VoloAlfa"));
        assertFalse(flightDescription.contains("Frecciarossa"));
    }

    @Test
    void omitsBlankFieldsAndKeepsPartialDetailsReadable() throws Exception {
        TravelItineraryDetails partial = new TravelItineraryDetails(List.of(
                new TravelLeg(" ", "Milano Centrale", "", null, " ", List.of("", "Firenze SMN", " "))),
                List.of(), true);

        JsonNode options = optimize(Map.of("treno", partial));
        String description = options.get(0).path("descrizioneDettagliata").asText();

        assertTrue(description.startsWith("Dettagli indicativi per treno"));
        assertTrue(description.contains("destinazione: Milano Centrale"));
        assertTrue(description.contains("fermate: Firenze SMN"));
        assertFalse(description.contains("partenza:"));
        assertFalse(description.contains("vettore:"));
        assertFalse(description.contains("; ;"));
        assertTrue(options.get(1).path("descrizioneDettagliata").asText().contains("non disponibili"));
    }

    @Test
    void usesTransparentIndicativeFallbackWhenDetailsAreMissingOrEmpty() throws Exception {
        JsonNode options = optimize(Map.of());

        assertEquals(3, options.size());
        for (JsonNode option : options) {
            String description = option.path("descrizioneDettagliata").asText();
            assertFalse(description.isBlank());
            assertTrue(description.contains("Itinerario indicativo"));
            assertTrue(description.contains("dettagli della tratta"));
            assertTrue(description.contains("Roma a Milano"));
            assertFalse(option.has("dettaglioViaggio"));
        }
    }

    private JsonNode optimize(Map<String, TravelItineraryDetails> details) throws Exception {
        CloudTravelTools cloudTravelTools = mock(CloudTravelTools.class);
        when(cloudTravelTools.cercaDettagliViaggio("Roma", "Milano", null)).thenReturn(details);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        TravelOptimizationEngine engine = new TravelOptimizationEngine(
                objectMapper, Clock.systemUTC(), executor, cloudTravelTools);

        try {
            String jobId = "description-test-" + System.nanoTime();
            engine.avviaOttimizzazione(jobId, "Roma", "Milano", 500);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (engine.getPayloadFinale(jobId).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            String payload = engine.getPayloadFinale(jobId).orElseThrow();
            return objectMapper.readTree(payload).path("opzioniViaggio");
        } finally {
            engine.shutdown();
        }
    }
}