package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import engineering.saint.travelbudgetoptimizer.dto.TravelItineraryDetails;
import engineering.saint.travelbudgetoptimizer.dto.TravelLeg;
import engineering.saint.travelbudgetoptimizer.dto.TravelOption;
import engineering.saint.travelbudgetoptimizer.dto.TravelOptimizationRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelDetailsCompatibilityTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyConstructorsAndJsonRemainCompatible() throws Exception {
        TravelOptimizationRequest request = new TravelOptimizationRequest("Roma", "Milano", 300);
        assertNull(request.dataPartenza());

        TravelOptimizationRequest legacyRequest = objectMapper.readValue(
                """
                        {"partenza":"Roma","destinazione":"Milano","budgetMassimo":300}
                        """,
                TravelOptimizationRequest.class);
        assertNull(legacyRequest.dataPartenza());

        TravelOption legacyOption = objectMapper.readValue(
                """
                        {"mezzo":"Treno","costoTotalizzato":100,"tempoPercorrenza":"3h",\
                        "descrizioneDettagliata":"Roma-Milano"}
                        """,
                TravelOption.class);
        assertNull(legacyOption.dettaglioViaggio());
        assertFalse(objectMapper.writeValueAsString(legacyOption).contains("dettaglioViaggio"));
    }

    @Test
        void acceptsOnlyCitedAndStructuredGeminiDetails() throws Exception {
        CloudTravelTools tools = tools();
        String content = """
                {"opzioni":[{"mezzo":"Treno","tratte":[
                  {"partenza":"Roma Termini","destinazione":"Milano Centrale",
                   "orarioPartenza":"09:00","orarioArrivo":"12:00",
                   "vettore":"Frecciarossa","fermate":["Firenze SMN"]}
                ]}]}
                """;
        String response = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", content))),
                        "groundingMetadata", Map.of("groundingChunks", List.of(Map.of(
                                "web", Map.of("uri", "https://example.test/timetable"))))))));

        Map<String, TravelItineraryDetails> parsed = tools.parseItinerarySuggestions(response, List.of());

        assertEquals("Frecciarossa", parsed.get("treno").tratte().getFirst().vettore());
        assertEquals(List.of("https://example.test/timetable"), parsed.get("treno").fonti());
        assertTrue(parsed.get("treno").indicativo());

        String uncited = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", content)))))));
        assertTrue(tools.parseItinerarySuggestions(uncited, List.of()).isEmpty());
    }

    @Test
    void allowsUnavailableFieldsWhenSomeRouteDetailIsKnown() throws Exception {
        CloudTravelTools tools = tools();
        String content = """
                {"opzioni":[{"mezzo":"Autobus","tratte":[{"fermate":["Firenze"]}]}]}
                """;
        String response = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", content))),
                        "groundingMetadata", Map.of("groundingChunks", List.of(Map.of(
                                "web", Map.of("uri", "https://example.test/route"))))))));

        TravelLeg leg = tools.parseItinerarySuggestions(response, List.of())
                .get("autobus").tratte().getFirst();

        assertNull(leg.partenza());
        assertEquals(List.of("Firenze"), leg.fermate());
    }

    @Test
    void parsesGoogleMapsTransitStopsAndCarrier() throws Exception {
        CloudTravelTools tools = tools();
        String response = """
                {
                  "status":"OK",
                  "routes":[{"legs":[{"steps":[{"transit_details":{
                    "line":{"name":"Frecciarossa","vehicle":{"type":"HIGH_SPEED_TRAIN"}},
                    "departure_stop":{"name":"Roma Termini"},
                    "arrival_stop":{"name":"Milano Centrale"},
                    "departure_time":{"text":"9:00 AM"},
                    "arrival_time":{"text":"12:00 PM"},
                    "stops":[{"name":"Firenze SMN"}]
                  }}]}]}]
                }
                """;

        Map<String, TravelItineraryDetails> details = tools.parseTransitDirections(response);

        assertEquals("Roma Termini", details.get("treno").tratte().getFirst().partenza());
        assertEquals("Firenze SMN", details.get("treno").tratte().getFirst().fermate().getFirst());
        assertEquals("Frecciarossa", details.get("treno").tratte().getFirst().vettore());
    }

    @Test
    void keepsBaselineProposalsWhenTravelSourcesFail() throws Exception {
        CloudTravelTools tools = mock(CloudTravelTools.class);
        LocalDate departure = LocalDate.of(2026, 12, 1);
        when(tools.cercaDettagliViaggio("Roma", "Milano", departure))
                .thenThrow(new IllegalStateException("provider unavailable"));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        TravelOptimizationEngine engine = new TravelOptimizationEngine(
                objectMapper, Clock.systemUTC(), executor, tools);

        try {
            engine.avviaOttimizzazione("compatibility-job", "Roma", "Milano", 500, departure);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (engine.getPayloadFinale("compatibility-job").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            var payload = objectMapper.readTree(engine.getPayloadFinale("compatibility-job").orElseThrow());
            var options = payload.path("opzioniViaggio");
            assertEquals(3, options.size());
            assertEquals("Treno", options.get(0).path("mezzo").asText());
            assertEquals(325.0, options.get(0).path("costoTotalizzato").asDouble());
            assertEquals("3h 30m", options.get(0).path("tempoPercorrenza").asText());
            assertEquals("Autobus", options.get(1).path("mezzo").asText());
            assertEquals(200.0, options.get(1).path("costoTotalizzato").asDouble());
            assertEquals("Aereo", options.get(2).path("mezzo").asText());
            assertEquals(450.0, options.get(2).path("costoTotalizzato").asDouble());
            for (var option : options) {
                assertFalse(option.has("dettaglioViaggio"));
            }
            verify(tools).cercaDettagliViaggio(eq("Roma"), eq("Milano"), eq(departure));
        } finally {
            engine.shutdown();
        }
    }

    private CloudTravelTools tools() {
        return new CloudTravelTools(
                org.springframework.web.client.RestClient.builder(),
                "https://maps.googleapis.com/maps/api",
                "fake-key",
                "https://api.search.brave.com/res/v1/web",
                "fake-key",
                "https://generativelanguage.googleapis.com",
                "fake-key",
                "https://chrome.browserless.io",
                "fake-key");
    }
}
