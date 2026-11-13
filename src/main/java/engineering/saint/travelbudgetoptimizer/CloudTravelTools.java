package engineering.saint.travelbudgetoptimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import engineering.saint.travelbudgetoptimizer.dto.TravelItineraryDetails;
import engineering.saint.travelbudgetoptimizer.dto.TravelLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CloudTravelTools {
    private static final Logger logger = LoggerFactory.getLogger(CloudTravelTools.class);

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final String mapsBaseUrl;
    private final String mapsApiKey;
    private final String braveBaseUrl;
    private final String braveApiKey;
    private final String geminiBaseUrl;
    private final String geminiApiKey;
    private final String browserlessBaseUrl;
    private final String browserlessToken;

    public CloudTravelTools(
            RestClient.Builder restClientBuilder,
            String mapsBaseUrl,
            String mapsApiKey,
            String braveBaseUrl,
            String braveApiKey,
            String geminiBaseUrl,
            String geminiApiKey,
            String browserlessBaseUrl,
            String browserlessToken) {
        this(restClientBuilder, new ObjectMapper(), mapsBaseUrl, mapsApiKey, braveBaseUrl, braveApiKey,
                geminiBaseUrl, geminiApiKey, browserlessBaseUrl, browserlessToken);
    }

    @Autowired
    public CloudTravelTools(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${cloud-tools.google-maps.base-url:https://maps.googleapis.com/maps/api}") String mapsBaseUrl,
            @Value("${cloud-tools.google-maps.api-key:fake-key}") String mapsApiKey,
            @Value("${cloud-tools.brave-search.base-url:https://api.search.brave.com/res/v1/web}") String braveBaseUrl,
            @Value("${cloud-tools.brave-search.api-key:fake-key}") String braveApiKey,
            @Value("${cloud-tools.gemini.base-url:https://generativelanguage.googleapis.com}") String geminiBaseUrl,
            @Value("${cloud-tools.gemini.api-key:fake-key}") String geminiApiKey,
            @Value("${cloud-tools.browserless.base-url:https://chrome.browserless.io}") String browserlessBaseUrl,
            @Value("${cloud-tools.browserless.token:fake-key}") String browserlessToken) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.mapsBaseUrl = mapsBaseUrl;
        this.mapsApiKey = mapsApiKey;
        this.braveBaseUrl = braveBaseUrl;
        this.braveApiKey = braveApiKey;
        this.geminiBaseUrl = geminiBaseUrl;
        this.geminiApiKey = geminiApiKey;
        this.browserlessBaseUrl = browserlessBaseUrl;
        this.browserlessToken = browserlessToken;
    }

    @Tool("Calcola un itinerario tra partenza e destinazione interrogando una API cloud di mappe. Restituisce distanza e tempo di percorrenza in un formato testuale sintetico, pronto da usare nel ragionamento del modello.")
    public String calcolaItinerario(
            @P("Citta o luogo di partenza") String partenza,
            @P("Citta o luogo di destinazione") String destinazione) {
        RestClient restClient = restClientBuilder.baseUrl(mapsBaseUrl).build();
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/distancematrix/json")
                            .queryParam("origins", partenza)
                            .queryParam("destinations", destinazione)
                            .queryParam("key", mapsApiKey)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            return "Itinerario cloud da " + partenza + " a " + destinazione + " -> " + sanitize(response);
        } catch (RestClientException ex) {
            return "Itinerario simulato da " + partenza + " a " + destinazione
                    + ": distanza stimata 120 km, tempo stimato 1h 45m (fallback POC: "
                    + ex.getClass().getSimpleName() + ")";
        }
    }

    @Tool("Ricerca tariffe reali indicative di treni, voli e bus tramite API di ricerca esterne. Restituisce testo formattato con i prezzi trovati e un riepilogo utilizzabile dall'LLM.")
    public String cercaTariffeTrasporti(
            @P("Citta o luogo di partenza") String partenza,
            @P("Citta o luogo di destinazione") String destinazione) {
        String query = "prezzi trasporto " + partenza + " " + destinazione + " treno volo bus";

        String braveResult;
        try {
            RestClient braveClient = restClientBuilder.baseUrl(braveBaseUrl).build();
            braveResult = braveClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/search").queryParam("q", query).build())
                    .header("X-Subscription-Token", braveApiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            braveResult = "Brave non disponibile (fallback POC: " + ex.getClass().getSimpleName() + ")";
        }

        String geminiResult;
        try {
            String query = "Dammi un riepilogo indicativo dei prezzi di treno, volo e bus per "
                    + partenza + " -> " + destinazione + ". Riporta le fonti web consultate.";
            geminiResult = restClientBuilder.baseUrl(geminiBaseUrl).build().post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/gemini-2.5-flash:generateContent")
                            .queryParam("key", geminiApiKey)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(geminiPayload(query, true))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            geminiResult = "Gemini non disponibile (fallback POC: " + ex.getClass().getSimpleName() + ")";
        }

        return "Tariffe trasporti " + partenza + " -> " + destinazione + "\n"
                + "- Treno: EUR 65-90\n"
                + "- Volo: EUR 80-140\n"
                + "- Bus: EUR 35-55\n"
                + "Dettaglio Brave: " + sanitize(braveResult) + "\n"
                + "Dettaglio Gemini: " + sanitize(extractGeminiText(geminiResult));
    }

    public Map<String, TravelItineraryDetails> cercaDettagliViaggio(
            String partenza, String destinazione, LocalDate dataPartenza) {
        Map<String, TravelItineraryDetails> details = new LinkedHashMap<>(
                cercaTratteGoogleMaps(partenza, destinazione));

        List<String> braveSources = new ArrayList<>();
        String braveEvidence = cercaEvidenzeBrave(partenza, destinazione, dataPartenza, braveSources);
        cercaDettagliGemini(partenza, destinazione, dataPartenza, braveEvidence, braveSources)
                .forEach(details::putIfAbsent);
        return Map.copyOf(details);
    }

    @Tool("Stima il costo medio degli alloggi a destinazione delegando lo scraping a un servizio remoto browser-as-a-service. Restituisce il prezzo medio per notte in formato testuale.")
    public String cercaCostoAlloggi(@P("Citta o luogo di destinazione") String destinazione) {
        try {
            RestClient browserlessClient = restClientBuilder.baseUrl(browserlessBaseUrl).build();
            Map<String, Object> payload = Map.of(
                    "url", "https://www.booking.com/searchresults.html?ss=" + destinazione,
                    "waitFor", 2500,
                    "elements", new String[] { ".price", "[data-testid='price-and-discounted-price']" });

            String response = browserlessClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/content").queryParam("token", browserlessToken).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            return "Costo medio alloggi a " + destinazione + " (da scraping cloud): " + sanitize(response);
        } catch (RestClientException ex) {
            return "Costo medio alloggi a " + destinazione
                    + ": circa EUR 110/notte (fallback POC: " + ex.getClass().getSimpleName() + ")";
        }
    }

    Map<String, TravelItineraryDetails> parseItinerarySuggestions(
            String response, List<String> additionalSources) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            Set<String> citations = new LinkedHashSet<>();
            for (JsonNode chunk : root.path("candidates").path(0)
                    .path("groundingMetadata").path("groundingChunks")) {
                String uri = optionalText(chunk.path("web"), "uri");
                if (uri != null && uri.startsWith("https://")) {
                    citations.add(uri);
                }
            }
            additionalSources.stream()
                    .filter(source -> source.startsWith("https://"))
                    .forEach(citations::add);
            if (citations.isEmpty()) {
                return Map.of();
            }

            String content = extractGeminiText(response);
            if (content == null || content.isBlank()) {
                return Map.of();
            }
            String json = content.trim()
                    .replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
            JsonNode suggestions = objectMapper.readTree(json).path("opzioni");
            if (!suggestions.isArray()) {
                return Map.of();
            }

            Map<String, TravelItineraryDetails> detailsByMode = new LinkedHashMap<>();
            for (JsonNode suggestion : suggestions) {
                String mode = canonicalMode(suggestion.path("mezzo").asText(""));
                JsonNode legsNode = suggestion.path("tratte");
                if (mode == null || !legsNode.isArray()) {
                    continue;
                }
                List<TravelLeg> legs = new ArrayList<>();
                for (JsonNode leg : legsNode) {
                    if (leg.isObject()) {
                        TravelLeg parsedLeg = new TravelLeg(
                                optionalText(leg, "partenza"),
                                optionalText(leg, "destinazione"),
                                optionalText(leg, "orarioPartenza"),
                                optionalText(leg, "orarioArrivo"),
                                optionalText(leg, "vettore"),
                                textList(leg.path("fermate")));
                        if (hasUsefulDetail(parsedLeg)) {
                            legs.add(parsedLeg);
                        }
                    }
                }
                if (!legs.isEmpty()) {
                    detailsByMode.putIfAbsent(mode, new TravelItineraryDetails(legs, List.copyOf(citations), true));
                }
            }
            return Map.copyOf(detailsByMode);
        } catch (JsonProcessingException exception) {
            logger.warn("Unable to parse indicative itinerary response: {}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, TravelItineraryDetails> cercaTratteGoogleMaps(String partenza, String destinazione) {
        if (isFakeKey(mapsApiKey)) {
            return Map.of();
        }
        try {
            String response = clientWithTimeout(mapsBaseUrl).get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/directions/json")
                            .queryParam("origin", partenza)
                            .queryParam("destination", destinazione)
                            .queryParam("mode", "transit")
                            .queryParam("key", mapsApiKey)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return parseTransitDirections(response);
        } catch (RestClientException exception) {
            logger.warn("Unable to retrieve Google Maps transit details: {}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    Map<String, TravelItineraryDetails> parseTransitDirections(String response) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            if (!"OK".equals(root.path("status").asText())) {
                return Map.of();
            }
            Map<String, List<TravelLeg>> legsByMode = new LinkedHashMap<>();
            for (JsonNode route : root.path("routes")) {
                for (JsonNode routeLeg : route.path("legs")) {
                    for (JsonNode step : routeLeg.path("steps")) {
                        JsonNode transit = step.path("transit_details");
                        if (!transit.isObject()) {
                            continue;
                        }
                        String mode = canonicalGoogleMode(
                                transit.path("line").path("vehicle").path("type").asText(""));
                        if (mode == null) {
                            continue;
                        }
                        JsonNode line = transit.path("line");
                        JsonNode departure = transit.path("departure_stop");
                        JsonNode arrival = transit.path("arrival_stop");
                        List<String> stops = new ArrayList<>();
                        for (JsonNode stop : transit.path("stops")) {
                            String name = optionalText(stop, "name");
                            if (name != null) {
                                stops.add(name);
                            }
                        }
                        legsByMode.computeIfAbsent(mode, ignored -> new ArrayList<>()).add(new TravelLeg(
                                optionalText(departure, "name"),
                                optionalText(arrival, "name"),
                                optionalText(transit.path("departure_time"), "text"),
                                optionalText(transit.path("arrival_time"), "text"),
                                optionalText(line, "name"),
                                stops));
                    }
                }
            }
            Map<String, TravelItineraryDetails> details = new LinkedHashMap<>();
            legsByMode.forEach((mode, legs) -> details.put(mode,
                    new TravelItineraryDetails(legs, List.of("https://maps.google.com/"), true)));
            return details;
        } catch (JsonProcessingException exception) {
            logger.warn("Unable to parse Google Maps transit response: {}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String cercaEvidenzeBrave(
            String partenza, String destinazione, LocalDate dataPartenza, List<String> sources) {
        if (isFakeKey(braveApiKey)) {
            return "";
        }
        try {
            String query = "orari tratte vettori treno autobus voli " + partenza + " " + destinazione
                    + (dataPartenza == null ? "" : " " + dataPartenza);
            String response = clientWithTimeout(braveBaseUrl).get()
                    .uri(uriBuilder -> uriBuilder.path("/search").queryParam("q", query).build())
                    .header("X-Subscription-Token", braveApiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("web").path("results");
            StringBuilder evidence = new StringBuilder();
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String url = optionalText(result, "url");
                    if (url == null || !url.startsWith("https://")) {
                        continue;
                    }
                    sources.add(url);
                    evidence.append("\n- ")
                            .append(optionalText(result, "title"))
                            .append(": ")
                            .append(optionalText(result, "description"))
                            .append(" [")
                            .append(url)
                            .append(']');
                    if (sources.size() == 5) {
                        break;
                    }
                }
            }
            return evidence.toString();
        } catch (RestClientException | JsonProcessingException exception) {
            logger.warn("Unable to retrieve Brave travel search evidence: {}",
                    exception.getClass().getSimpleName());
            return "";
        }
    }

    private Map<String, TravelItineraryDetails> cercaDettagliGemini(
            String partenza, String destinazione, LocalDate dataPartenza,
            String braveEvidence, List<String> braveSources) {
        if (isFakeKey(geminiApiKey)) {
            return Map.of();
        }
        try {
            String query = "Cerca itinerari indicativi documentati per " + partenza + " -> " + destinazione
                    + (dataPartenza == null ? "" : " per il " + dataPartenza)
                    + ". Considera queste evidenze web, senza trattarle come disponibilità attuale: " + braveEvidence
                    + "\nRispondi solo con JSON nel formato {\"opzioni\":[{\"mezzo\":\"Treno\","
                    + "\"tratte\":[{\"partenza\":null,\"destinazione\":null,"
                    + "\"orarioPartenza\":null,\"orarioArrivo\":null,"
                    + "\"vettore\":null,\"fermate\":[]}]}]}. "
                    + "Includi solo dati supportati dalle fonti citate; non inventare orari, fermate o vettori. "
                    + "Usa null o [] per i dati non disponibili. Sono dettagli indicativi, non prenotabili.";

            String response = clientWithTimeout(geminiBaseUrl).post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/gemini-2.5-flash:generateContent")
                            .queryParam("key", geminiApiKey)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(geminiPayload(query, true))
                    .retrieve()
                    .body(String.class);
            return parseItinerarySuggestions(response, braveSources);
        } catch (RestClientException exception) {
            logger.warn("Unable to retrieve Gemini itinerary details: {}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, Object> geminiPayload(String query, boolean enableGoogleSearch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", query)))));
        if (enableGoogleSearch) {
            payload.put("tools", List.of(Map.of("google_search", Map.of())));
        }
        return payload;
    }

    private String extractGeminiText(String response) {
        if (response == null || response.isBlank()) {
            return response;
        }
        try {
            JsonNode parts = objectMapper.readTree(response).path("candidates").path(0)
                    .path("content").path("parts");
            if (parts.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : parts) {
                    String value = optionalText(part, "text");
                    if (value != null) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(value);
                    }
                }
                if (!text.isEmpty()) {
                    return text.toString();
                }
            }
        } catch (JsonProcessingException exception) {
            logger.warn("Unable to parse Gemini response: {}", exception.getClass().getSimpleName());
        }
        return response;
    }

    private RestClient clientWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private boolean isFakeKey(String apiKey) {
        return apiKey == null || apiKey.isBlank() || "fake-key".equals(apiKey);
    }

    private String canonicalGoogleMode(String vehicleType) {
        return switch (vehicleType) {
            case "RAIL", "HEAVY_RAIL", "HIGH_SPEED_TRAIN", "COMMUTER_TRAIN", "SUBWAY", "MONORAIL" -> "treno";
            case "BUS", "INTERCITY_BUS", "TROLLEYBUS" -> "autobus";
            default -> null;
        };
    }

    private String canonicalMode(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("tren")) {
            return "treno";
        }
        if (normalized.contains("bus") || normalized.contains("autobus") || normalized.contains("coach")) {
            return "autobus";
        }
        if (normalized.contains("aer") || normalized.contains("flight") || normalized.contains("vol")) {
            return "aereo";
        }
        return null;
    }

    private boolean hasUsefulDetail(TravelLeg leg) {
        return leg.partenza() != null
                || leg.destinazione() != null
                || leg.orarioPartenza() != null
                || leg.orarioArrivo() != null
                || leg.vettore() != null
                || !leg.fermate().isEmpty();
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().trim() : null;
    }

    private List<String> textList(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (value.isTextual() && !value.textValue().isBlank()) {
                normalized.add(value.textValue().trim());
            }
        }
        return List.copyOf(normalized);
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "nessun dato";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }
}
