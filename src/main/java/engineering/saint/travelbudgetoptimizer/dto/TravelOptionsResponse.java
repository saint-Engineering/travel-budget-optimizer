package engineering.saint.travelbudgetoptimizer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelOptionsResponse(List<TravelOption> opzioniViaggio) {

    public TravelOptionsResponse {
        if (opzioniViaggio == null) {
            throw new IllegalArgumentException("opzioniViaggio must not be null");
        }
        if (opzioniViaggio.size() != 3) {
            throw new IllegalArgumentException("opzioniViaggio must contain exactly 3 elements");
        }
        opzioniViaggio = List.copyOf(opzioniViaggio);
    }
}
