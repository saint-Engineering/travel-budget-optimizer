package engineering.saint.travelbudgetoptimizer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelItineraryDetails(
        List<TravelLeg> tratte,
        List<String> fonti,
        boolean indicativo) {

    public TravelItineraryDetails {
        tratte = tratte == null ? List.of() : List.copyOf(tratte);
        fonti = fonti == null ? List.of() : List.copyOf(fonti);
    }
}
