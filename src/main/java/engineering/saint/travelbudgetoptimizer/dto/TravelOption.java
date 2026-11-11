package engineering.saint.travelbudgetoptimizer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelOption(
        String mezzo,
        double costoTotalizzato,
        String tempoPercorrenza,
        String descrizioneDettagliata,
        TravelItineraryDetails dettaglioViaggio) {

    public TravelOption(String mezzo, double costoTotalizzato, String tempoPercorrenza,
                        String descrizioneDettagliata) {
        this(mezzo, costoTotalizzato, tempoPercorrenza, descrizioneDettagliata, null);
    }
}
