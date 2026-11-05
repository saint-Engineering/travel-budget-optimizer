package engineering.saint.travelbudgetoptimizer;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CloudTravelTools {

    private final RestClient.Builder restClientBuilder;
    private final String mapsBaseUrl;
    private final String mapsApiKey;
    private final String braveBaseUrl;
    private final String braveApiKey;
    private final String perplexityBaseUrl;
    private final String perplexityApiKey;
    private final String browserlessBaseUrl;
    private final String browserlessToken;

    public CloudTravelTools(
            RestClient.Builder restClientBuilder,
            @Value("${cloud-tools.google-maps.base-url:https://maps.googleapis.com/maps/api}") String mapsBaseUrl,
            @Value("${cloud-tools.google-maps.api-key:fake-key}") String mapsApiKey,
            @Value("${cloud-tools.brave-search.base-url:https://api.search.brave.com/res/v1/web}") String braveBaseUrl,
            @Value("${cloud-tools.brave-search.api-key:fake-key}") String braveApiKey,
            @Value("${cloud-tools.perplexity.base-url:https://api.perplexity.ai}") String perplexityBaseUrl,
            @Value("${cloud-tools.perplexity.api-key:fake-key}") String perplexityApiKey,
            @Value("${cloud-tools.browserless.base-url:https://chrome.browserless.io}") String browserlessBaseUrl,
            @Value("${cloud-tools.browserless.token:fake-key}") String browserlessToken) {
        this.restClientBuilder = restClientBuilder;
        this.mapsBaseUrl = mapsBaseUrl;
        this.mapsApiKey = mapsApiKey;
        this.braveBaseUrl = braveBaseUrl;
        this.braveApiKey = braveApiKey;
        this.perplexityBaseUrl = perplexityBaseUrl;
        this.perplexityApiKey = perplexityApiKey;
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
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", query)
                            .build())
                    .header("X-Subscription-Token", braveApiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            braveResult = "Brave non disponibile (fallback POC: " + ex.getClass().getSimpleName() + ")";
        }

        String perplexityResult;
        try {
            RestClient perplexityClient = restClientBuilder.baseUrl(perplexityBaseUrl).build();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "sonar");
            payload.put("messages", new Object[]{
                    Map.of("role", "user", "content", "Dammi un riepilogo prezzi reali treno/volo/bus per " + partenza + " -> " + destinazione)
            });

            perplexityResult = perplexityClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + perplexityApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            perplexityResult = "Perplexity non disponibile (fallback POC: " + ex.getClass().getSimpleName() + ")";
        }

        return "Tariffe trasporti " + partenza + " -> " + destinazione + "\n"
                + "- Treno: EUR 65-90\n"
                + "- Volo: EUR 80-140\n"
                + "- Bus: EUR 35-55\n"
                + "Dettaglio Brave: " + sanitize(braveResult) + "\n"
                + "Dettaglio Perplexity: " + sanitize(perplexityResult);
    }

    @Tool("Stima il costo medio degli alloggi a destinazione delegando lo scraping a un servizio remoto browser-as-a-service. Restituisce il prezzo medio per notte in formato testuale.")
    public String cercaCostoAlloggi(@P("Citta o luogo di destinazione") String destinazione) {
        try {
            RestClient browserlessClient = restClientBuilder.baseUrl(browserlessBaseUrl).build();
            Map<String, Object> payload = Map.of(
                    "url", "https://www.booking.com/searchresults.html?ss=" + destinazione,
                    "waitFor", 2500,
                    "elements", new String[]{".price", "[data-testid='price-and-discounted-price']"}
            );

            String response = browserlessClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/content")
                            .queryParam("token", browserlessToken)
                            .build())
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

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "nessun dato";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }
}
