package engineering.saint.travelbudgetoptimizer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelLeg(
        String partenza,
        String destinazione,
        String orarioPartenza,
        String orarioArrivo,
        String vettore,
        List<String> fermate) {

    public TravelLeg {
        fermate = fermate == null ? List.of() : List.copyOf(fermate);
    }
}
