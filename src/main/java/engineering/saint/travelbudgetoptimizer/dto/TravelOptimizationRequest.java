package engineering.saint.travelbudgetoptimizer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TravelOptimizationRequest(
        String partenza,
        String destinazione,
        @JsonProperty("budgetMassimo") double budgetMassimo) {

    public TravelOptimizationRequest {
        if (partenza == null || partenza.isBlank()) {
            throw new IllegalArgumentException("partenza must not be blank");
        }
        if (destinazione == null || destinazione.isBlank()) {
            throw new IllegalArgumentException("destinazione must not be blank");
        }
        if (!Double.isFinite(budgetMassimo) || budgetMassimo < 0) {
            throw new IllegalArgumentException("budgetMassimo must be finite and non-negative");
        }
    }
}